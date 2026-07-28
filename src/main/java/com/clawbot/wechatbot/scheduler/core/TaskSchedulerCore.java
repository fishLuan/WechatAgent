package com.clawbot.wechatbot.scheduler.core;

import com.clawbot.wechatbot.base.MessageSender;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.clawbot.wechatbot.scheduler.task.ScheduledTaskContentProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class TaskSchedulerCore implements SmartLifecycle {

    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ZoneId SHANGHAI_ZONE = SHANGHAI.toZoneId();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConcurrentTaskScheduler scheduler = new ConcurrentTaskScheduler();
    private final Map<String, ScheduledFuture<?>> runningFutures = new ConcurrentHashMap<>();

    private final MessageSender messageSender;
    private final Map<TaskType, ScheduledTaskContentProvider> providerMap;

    private volatile boolean running;

    public TaskSchedulerCore(@Lazy MessageSender messageSender,
                             List<ScheduledTaskContentProvider> providers) {
        this.messageSender = messageSender;
        this.providerMap = new ConcurrentHashMap<>();
        for (ScheduledTaskContentProvider p : providers) {
            providerMap.put(p.taskType(), p);
        }
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        System.out.println("[SCHEDULER-CORE] 调度核心启动 Provider=" + providerMap.keySet() + " 时区=Asia/Shanghai");
    }

    @Override
    @PreDestroy
    public synchronized void stop() {
        int count = 0;
        for (Map.Entry<String, ScheduledFuture<?>> e : runningFutures.entrySet()) {
            e.getValue().cancel(false);
            count++;
        }
        runningFutures.clear();
        running = false;
        System.out.println("[SCHEDULER-CORE] 调度核心优雅关闭，已停止 " + count + " 个定时任务");
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 5; }

    public synchronized void register(ScheduledSubscription subscription) {
        ScheduledFuture<?> old = runningFutures.remove(subscription.getId());
        if (old != null) old.cancel(false);

        if (!subscription.isEnabled()) {
            System.out.println("[SCHEDULER-CORE] 订阅已禁用，不注册 subId=" + subscription.getId());
            return;
        }

        try {
            // ========== 分支1：单次提醒 ONE_TIME_REMINDER（只发一次，发完自毁） ==========
            if (subscription.getTaskType() == com.clawbot.wechatbot.scheduler.model.TaskType.ONE_TIME_REMINDER) {
                long fireAt = 0L;
                String messageContent = "";
                boolean alreadyFired = false;
                try {
                    if (subscription.getParamsJson() != null && !subscription.getParamsJson().isBlank()) {
                        com.fasterxml.jackson.databind.JsonNode p = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(subscription.getParamsJson());
                        if (p != null && p.isObject()) {
                            fireAt = p.path("fire_timestamp").asLong(0L);
                            messageContent = p.path("message_content").asText("");
                            alreadyFired = p.path("already_fired").asBoolean(false);
                        }
                    }
                } catch (Exception ignored) {}
                if (alreadyFired) {
                    System.out.println("[SCHEDULER-CORE] 单次提醒已发送过，跳过 subId=" + subscription.getId());
                    return;
                }
                if (fireAt <= 0) fireAt = System.currentTimeMillis() + 60_000L;
                if (fireAt <= System.currentTimeMillis()) {
                    System.out.println("[SCHEDULER-CORE] 单次提醒已过期，跳过 subId=" + subscription.getId()
                        + " 触发时间=" + LocalDateTime.ofInstant(Instant.ofEpochMilli(fireAt), SHANGHAI_ZONE).format(DTF));
                    return;
                }
                Instant fireInstant = Instant.ofEpochMilli(fireAt);
                String fireTimeStr = LocalDateTime.ofInstant(fireInstant, SHANGHAI_ZONE).format(DTF);
                String finalMessageContent = messageContent;
                long finalFireAt = fireAt;
                ScheduledFuture<?> future = scheduler.schedule(() -> {
                    if (!messageSender.isReady()) {
                        System.err.println("[SCHEDULER-CORE] 单次提醒发送器未就绪，跳过 subId=" + subscription.getId());
                        return;
                    }
                    try {
                        ScheduledTaskContentProvider provider = providerMap.get(subscription.getTaskType());
                        if (provider == null) provider = providerMap.get(com.clawbot.wechatbot.scheduler.model.TaskType.SIMPLE_TEXT);
                        String text = (provider != null)
                            ? provider.provideContent(subscription.getUserId(), subscription.getParamsJson())
                            : (finalMessageContent.isBlank() ? "⏰ 你的单次提醒到啦！" : finalMessageContent);
                        if (text != null && !text.isBlank()) {
                            messageSender.sendText(subscription.getUserId(), text);
                            System.out.println("[SCHEDULER-CORE] ✅【单次提醒】已发送 subId=" + subscription.getId());
                        }
                    } catch (Exception ex) {
                        System.err.println("[SCHEDULER-CORE] 单次提醒执行失败 subId=" + subscription.getId() + " " + ex.getMessage());
                    } finally {
                        // 【关键：发完立刻自毁！】① 内存 cancel 掉 ScheduledFuture（已经是一次性的，但保险起见）
                        cancel(subscription.getId());
                        // ② 把「已发送」标记写回 paramsJson + disabled=true 存 MongoDB，保证重启也不会再发
                        try {
                            ScheduledSubscription s = subscription;
                            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.node.ObjectNode newParams = om.createObjectNode();
                            try {
                                if (s.getParamsJson() != null && !s.getParamsJson().isBlank()) {
                                    com.fasterxml.jackson.databind.JsonNode oldP = om.readTree(s.getParamsJson());
                                    if (oldP != null && oldP.isObject()) {
                                        oldP.fields().forEachRemaining(e -> newParams.set(e.getKey(), e.getValue()));
                                    }
                                }
                            } catch (Exception ignored) {}
                            newParams.put("already_fired", true);
                            newParams.put("fired_at", System.currentTimeMillis());
                            s.setParamsJson(om.writeValueAsString(newParams));
                            s.setEnabled(false);
                            // 从 Spring 上下文拿 MongoTemplate 存一下（避免循环依赖，手动拿 Bean）
                            Object mongoTemplate = org.springframework.web.context.ContextLoader
                                .getCurrentWebApplicationContext() != null
                                ? org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext()
                                    .getBean(org.springframework.data.mongodb.core.MongoTemplate.class)
                                : null;
                            if (mongoTemplate != null) {
                                ((org.springframework.data.mongodb.core.MongoTemplate) mongoTemplate).save(s);
                            }
                        } catch (Exception ignored) {}
                    }
                }, fireInstant);

                runningFutures.put(subscription.getId(), future);
                System.out.println("[SCHEDULER-CORE] ⏰【单次提醒】注册 subId=" + subscription.getId()
                    + " 触发时间(北京时间)=" + fireTimeStr);
                return;
            }

            // ========== 分支2：原有的每天重复 Cron 订阅 ==========
            CronTrigger trigger = new CronTrigger(subscription.getCronExpression(), SHANGHAI);
            Instant nextExec = trigger.nextExecution(new org.springframework.scheduling.support.SimpleTriggerContext(
                Instant.now(), Instant.now(), Instant.now()));
            String nextTimeStr = nextExec == null ? "计算失败" :
                LocalDateTime.ofInstant(nextExec, SHANGHAI_ZONE).format(DTF);
            System.out.println("[SCHEDULER-CORE] 注册成功 subId=" + subscription.getId()
                + " Cron=[" + subscription.getCronExpression() + "] 下次触发=" + nextTimeStr);

            ScheduledFuture<?> future = scheduler.schedule(() -> {
                if (!messageSender.isReady()) {
                    System.err.println("[SCHEDULER-CORE] 发送器未就绪，跳过 subId=" + subscription.getId());
                    return;
                }
                try {
                    ScheduledTaskContentProvider provider = providerMap.get(subscription.getTaskType());
                    if (provider == null) {
                        System.err.println("[SCHEDULER-CORE] 无匹配 Provider type=" + subscription.getTaskType());
                        return;
                    }
                    String text = provider.provideContent(subscription.getUserId(), subscription.getParamsJson());
                    if (text != null && !text.isBlank()) {
                        messageSender.sendText(subscription.getUserId(), text);
                        System.out.println("[SCHEDULER-CORE] 发送成功 subId=" + subscription.getId());
                    }
                } catch (Exception ex) {
                    System.err.println("[SCHEDULER-CORE] 执行失败 subId=" + subscription.getId() + " " + ex.getMessage());
                }
            }, trigger);

            runningFutures.put(subscription.getId(), future);
        } catch (Exception ex) {
            System.err.println("[SCHEDULER-CORE] 注册失败 subId=" + subscription.getId()
                + " Cron=[" + subscription.getCronExpression() + "] " + ex.getMessage());
        }
    }

    public synchronized void cancel(String subscriptionId) {
        ScheduledFuture<?> future = runningFutures.remove(subscriptionId);
        if (future != null) {
            future.cancel(false);
            System.out.println("[SCHEDULER-CORE] 已取消定时任务 subId=" + subscriptionId);
        }
    }
}