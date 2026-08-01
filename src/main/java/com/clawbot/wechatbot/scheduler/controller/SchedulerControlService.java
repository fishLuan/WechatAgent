package com.clawbot.wechatbot.scheduler.controller;

import com.clawbot.wechatbot.scheduler.core.TaskSchedulerCore;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;

@Service
public class SchedulerControlService implements SmartLifecycle {

    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ZoneId SHANGHAI_ZONE = SHANGHAI.toZoneId();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MongoTemplate mongoTemplate;
    private final TaskSchedulerCore schedulerCore;
    private volatile boolean running;

    public SchedulerControlService(MongoTemplate mongoTemplate, TaskSchedulerCore schedulerCore) {
        this.mongoTemplate = mongoTemplate;
        this.schedulerCore = schedulerCore;
    }

    /** 给外部（SchedulerTool）用：根据 Cron 计算下一次触发时间（北京时间，直接返回格式化好的字符串给 AI 看） */
    public static String calcNextFireTime(String cronExpression) {
        try {
            CronTrigger trigger = new CronTrigger(cronExpression, SHANGHAI);
            Instant next = trigger.nextExecution(new SimpleTriggerContext(Instant.now(), Instant.now(), Instant.now()));
            if (next == null) return "无法计算";
            return LocalDateTime.ofInstant(next, SHANGHAI_ZONE).format(DTF);
        } catch (Exception e) {
            return "Cron 表达式错误：" + e.getMessage();
        }
    }

    /** 【单次提醒专用】把 日期时间字符串 转成北京时间的时间戳（毫秒）
     *  支持格式："2026-07-28 15:00"、"今天 15:00"、"明天 15:00"、"15:00"（默认今天）
     */
    public static long parseOneTimeFireAt(String datetimeStr) {
        try {
            if (datetimeStr == null || datetimeStr.isBlank()) return 0L;
            String s = datetimeStr.trim().replace('：', ':')
                .replaceAll("[\\s\\u00a0]", " ").replace("  ", " ").trim();

            LocalDateTime now = LocalDateTime.now(SHANGHAI_ZONE);
            java.util.regex.Matcher mHhmm = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(s);

            // 情况1：只有 "HH:mm" → 今天
            if (mHhmm.matches()) {
                LocalDateTime t = now.withHour(Integer.parseInt(mHhmm.group(1)))
                    .withMinute(Integer.parseInt(mHhmm.group(2))).withSecond(0).withNano(0);
                if (t.isBefore(now)) t = t.plusDays(1);
                return t.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli();
            }
            // 情况2："今天 HH:mm" / "明天 HH:mm"
            if (s.startsWith("今天") || s.startsWith("明天")) {
                boolean isTomorrow = s.startsWith("明天");
                String hhmmPart = s.replace("今天", "").replace("明天", "").trim();
                java.util.regex.Matcher mHhmm2 = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(hhmmPart);
                if (mHhmm2.matches()) {
                    LocalDateTime d = now.withHour(Integer.parseInt(mHhmm2.group(1)))
                        .withMinute(Integer.parseInt(mHhmm2.group(2))).withSecond(0).withNano(0);
                    if (isTomorrow) d = d.plusDays(1);
                    if (!isTomorrow && d.isBefore(now)) d = d.plusDays(1);
                    return d.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli();
                }
            }
            // 情况3："yyyy-MM-dd HH:mm"
            java.util.regex.Matcher mFull = java.util.regex.Pattern
                .compile("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})[日\\s]*(\\d{1,2}):(\\d{2})$").matcher(s);
            if (mFull.matches()) {
                LocalDateTime d = LocalDateTime.of(
                    Integer.parseInt(mFull.group(1)),
                    Integer.parseInt(mFull.group(2)),
                    Integer.parseInt(mFull.group(3)),
                    Integer.parseInt(mFull.group(4)),
                    Integer.parseInt(mFull.group(5)), 0, 0);
                return d.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli();
            }
            // 情况4："MM-dd HH:mm"（默认今年）
            java.util.regex.Matcher mNoYear = java.util.regex.Pattern
                .compile("^(\\d{1,2})[-/月](\\d{1,2})[日\\s]*(\\d{1,2}):(\\d{2})$").matcher(s);
            if (mNoYear.matches()) {
                LocalDateTime d = LocalDateTime.of(
                    now.getYear(),
                    Integer.parseInt(mNoYear.group(1)),
                    Integer.parseInt(mNoYear.group(2)),
                    Integer.parseInt(mNoYear.group(3)),
                    Integer.parseInt(mNoYear.group(4)), 0, 0);
                if (d.isBefore(now)) d = d.plusYears(1);
                return d.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli();
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;

        // 【强力清理-解决脏数据】启动时先把属于测试用户的「每分钟的调试订阅（abc123）」全部自动禁用，防止骚扰
        try {
            List<ScheduledSubscription> allSubs = mongoTemplate.findAll(ScheduledSubscription.class);
            int autoCleaned = 0;
            for (ScheduledSubscription s : allSubs) {
                boolean shouldClean = false;
                // 条件1：Cron 是每分钟（0 * * * * ?）→ 调试用的，自动清
                if ("0 * * * * ?".equals(s.getCronExpression())) shouldClean = true;
                // 条件2：内容包含「abc123」→ 调试内容，自动清
                if (s.getParamsJson() != null && s.getParamsJson().contains("abc123")) shouldClean = true;
                if (shouldClean && s.isEnabled()) {
                    s.setEnabled(false);
                    mongoTemplate.save(s);
                    schedulerCore.cancel(s.getId());
                    autoCleaned++;
                }
            }
            if (autoCleaned > 0) {
                System.out.println("[SCHEDULER-CTRL] 🧹 启动自动清理：已禁用 " + autoCleaned
                    + " 条调试/脏数据订阅（每分钟abc123）");
            }
        } catch (Exception ignored) {}

        List<ScheduledSubscription> enabledSubs = mongoTemplate.find(
                Query.query(Criteria.where("enabled").is(true)), ScheduledSubscription.class);
        int skippedOneTime = 0;
        for (ScheduledSubscription sub : enabledSubs) {
            // 单次任务：过滤掉「已经发过」或「触发时间已过期」的，自动置 disabled
            if (isOneTime(sub)) {
                boolean skip = false;
                try {
                    if (sub.getParamsJson() != null && !sub.getParamsJson().isBlank()) {
                        com.fasterxml.jackson.databind.JsonNode p = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(sub.getParamsJson());
                        if (p != null && p.isObject()) {
                            if (p.path("already_fired").asBoolean(false)) skip = true;
                            long fireAt = p.path("fire_timestamp").asLong(0L);
                            if (fireAt > 0 && fireAt < System.currentTimeMillis()) skip = true;
                        }
                    }
                } catch (Exception ignored) {}
                if (skip) {
                    sub.setEnabled(false);
                    mongoTemplate.save(sub);
                    skippedOneTime++;
                    continue;
                }
            }
            schedulerCore.register(sub);
        }
        int actualRestored = enabledSubs.size() - skippedOneTime;
        String skipMsg = skippedOneTime > 0 ? "（已自动跳过 " + skippedOneTime + " 个过期/已发的单次提醒）" : "";
        System.out.println("[SCHEDULER-CTRL] 启动：从 MongoDB 恢复了 " + actualRestored + " 个有效订阅" + skipMsg);
    }

    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 8; }

    /** 单次任务：一次性提醒，或 paramsJson 带 fire_timestamp（含一次性B站推送） */
    private boolean isOneTime(ScheduledSubscription sub) {
        if (sub.getTaskType() == TaskType.ONE_TIME_REMINDER) return true;
        try {
            if (sub.getParamsJson() != null && !sub.getParamsJson().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode p = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(sub.getParamsJson());
                if (p != null && p.isObject()) {
                    return p.path("fire_timestamp").asLong(0L) > 0L;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public ScheduledSubscription createOrUpdate(ScheduledSubscription subscription) {
        if (subscription.getCreatedAt() == null) subscription.setCreatedAt(System.currentTimeMillis());
        ScheduledSubscription saved = mongoTemplate.save(subscription);
        schedulerCore.register(saved);
        return saved;
    }

    public boolean cancelByUserAndType(String userId, String taskTypeStr) {
        try {
            TaskType tt = Enum.valueOf(TaskType.class, taskTypeStr);
            ScheduledSubscription sub = mongoTemplate.findOne(
                    Query.query(Criteria.where("userId").is(userId).and("taskType").is(tt).and("enabled").is(true)),
                    ScheduledSubscription.class);
            if (sub == null) return false;
            schedulerCore.cancel(sub.getId());
            sub.setEnabled(false);
            mongoTemplate.save(sub);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 按订阅 ID 精确取消（最常用，不会误删） */
    public boolean cancelBySubscriptionId(String subscriptionId, String userId) {
        if (subscriptionId == null || subscriptionId.isBlank()) return false;
        ScheduledSubscription sub = mongoTemplate.findById(subscriptionId, ScheduledSubscription.class);
        if (sub == null) return false;
        // 校验归属，防止跨用户乱删
        if (userId != null && !userId.isBlank() && !userId.equals(sub.getUserId())) return false;
        schedulerCore.cancel(sub.getId());
        sub.setEnabled(false);
        mongoTemplate.save(sub);
        return true;
    }

    /** 取消用户全部订阅（一键清干净） */
    public int cancelAllByUser(String userId) {
        List<ScheduledSubscription> subs = mongoTemplate.find(
                Query.query(Criteria.where("userId").is(userId).and("enabled").is(true)),
                ScheduledSubscription.class);
        int count = 0;
        for (ScheduledSubscription sub : subs) {
            schedulerCore.cancel(sub.getId());
            sub.setEnabled(false);
            mongoTemplate.save(sub);
            count++;
        }
        return count;
    }

    public List<ScheduledSubscription> listByUser(String userId) {
        return mongoTemplate.find(
                Query.query(Criteria.where("userId").is(userId)), ScheduledSubscription.class);
    }

    public static String timeToDailyCron(String hhmm) {
        try {
            if (hhmm == null || hhmm.isBlank()) return "0 0 9 * * ?";
            // 【关键修复】先把所有中文全角冒号「：」替换成英文半角「:」
            String normalized = hhmm.trim().replace('：', ':').replaceAll("[\\s\\u00a0]", "");
            String[] parts = normalized.split(":");
            if (parts.length < 2) return "0 0 9 * * ?";
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return "0 0 9 * * ?";
            return String.format("0 %d %d * * ?", m, h);
        } catch (Exception e) {
            // 解析失败不打红日志，正常容错（用户可能打错了格式），直接回退默认9点
            return "0 0 9 * * ?";
        }
    }

    /** 把 Cron 表达式转成用户能看懂的文字描述（目前只支持「每天 HH:mm」格式，其他直接返回原 Cron） */
    public static String cronToTimeDesc(String cron) {
        try {
            if (cron == null || cron.isBlank()) return "每天";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^0\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+\\*\\s+\\*\\s+\\?$").matcher(cron.trim());
            if (m.matches()) {
                int h = Integer.parseInt(m.group(2));
                int mm = Integer.parseInt(m.group(1));
                String period = (h >= 0 && h < 6) ? "凌晨" : (h < 12) ? "上午" : (h == 12) ? "中午" : (h < 18) ? "下午" : "晚上";
                return period + " " + String.format("%02d:%02d（每天）", h, mm);
            }
        } catch (Exception ignored) {}
        return "Cron=" + cron;
    }
}