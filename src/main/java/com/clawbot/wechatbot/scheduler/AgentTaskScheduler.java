package com.clawbot.wechatbot.scheduler;

import com.clawbot.wechatbot.notification.NotificationService;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class AgentTaskScheduler implements SmartLifecycle {

    private static final int MAX_RETRY = 3;
    private static final long[] RETRY_DELAYS_SEC = {60, 300, 900};

    private final TaskSchedulerProperties props;
    private final TaskPersistence persistence;
    private final NotificationService notifications;
    private final WeChatMessageSender sender;
    private final ZoneId tz;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final ConcurrentHashMap<String, TaskHolder> registry = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private record TaskHolder(ScheduledFuture<?> future, ScheduledTask task) {}

    public AgentTaskScheduler(
        TaskSchedulerProperties props,
        TaskPersistence persistence,
        NotificationService notifications,
        WeChatMessageSender sender
    ) {
        this.props = props;
        this.persistence = persistence;
        this.notifications = notifications;
        this.sender = sender;
        this.tz = ZoneId.of(props.getDefaultTimezone());
    }

    // ====================================
    // SmartLifecycle
    // ====================================

    @Override
    public int getPhase() { return 100; }

    @Override
    public boolean isAutoStartup() { return props.isEnabled(); }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public synchronized void start() {
        if (running.get()) return;
        running.set(true);

        scheduler.setPoolSize(props.getPoolSize());
        scheduler.setThreadNamePrefix("agent-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(props.getAwaitTerminationSeconds());
        scheduler.setErrorHandler(t -> {
            System.err.println("[SCHEDULER] 未捕获异常: " + t.getMessage());
            t.printStackTrace();
            notifications.notifyError("调度器线程池未捕获异常", t);
        });
        scheduler.initialize();
        System.out.printf("[SCHEDULER] ▶ 启动 AgentTaskScheduler（phase=%d，线程池=%d，持久化=%s）%n",
            getPhase(), props.getPoolSize(), props.isPersistenceEnabled() ? "ON" : "OFF");

        int restored = 0;
        int dropped = 0;
        if (props.isPersistenceEnabled()) {
            List<ScheduledTask> pending = persistence.findAllActive();
            System.out.printf("[SCHEDULER] 💾 MongoDB 读到 PENDING 状态任务 %d 条%n", pending.size());
            for (ScheduledTask t : pending) {
                try {
                    // 一次性任务过期超过容忍毫秒就标记 FAILED，不再触发
                    if (t.type() == ScheduledTask.TaskType.ONCE
                        && t.executeAt() != null
                        && t.executeAt().isBefore(Instant.now())) {
                        long lateMs = System.currentTimeMillis() - t.executeAt().toEpochMilli();
                        if (lateMs > props.getOneshotRestoreToleranceMs()) {
                            System.out.printf("[SCHEDULER] ⏩ 跳过过期一次性任务 id=%s  触发=%s  迟到=%d秒>%d秒  内容=%s%n",
                                shortId(t.id()),
                                t.executeAt().atZone(tz).toLocalTime().withNano(0),
                                lateMs / 1000, props.getOneshotRestoreToleranceMs() / 1000, t.name());
                            markAndPersist(t.withStatus(ScheduledTask.TaskStatus.COMPLETED));
                            dropped++;
                            continue;
                        } else {
                            System.out.printf("[SCHEDULER] ⏱ 一次性任务迟到 %d 秒，仍会立即执行 id=%s  内容=%s%n",
                                lateMs / 1000, shortId(t.id()), t.name());
                        }
                    }
                    reSchedule(t);
                    restored++;
                } catch (Exception e) {
                    System.err.println("[SCHEDULER] ❌ 任务恢复失败/" + t.name() + ": " + e.getMessage());
                    e.printStackTrace();
                    notifications.notifyError("任务恢复失败/" + t.name(), e);
                    markAndPersist(t.withStatus(ScheduledTask.TaskStatus.FAILED));
                }
            }
        }
        System.out.printf(
            "[SCHEDULER] ✅ 启动完成，恢复待执行任务=%d，丢弃过期一次性=%d%n",
            restored, dropped
        );
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public synchronized void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        System.out.println("[SCHEDULER] 开始优雅关闭...");

        int cancelled = 0, runningCount = 0;
        for (TaskHolder h : registry.values()) {
            if (h.future().cancel(false)) cancelled++;
            else runningCount++;
        }
        System.out.printf(
            "[SCHEDULER] 取消排队任务=%d，等待运行中任务完成=%d%n", cancelled, runningCount
        );

        if (props.isPersistenceEnabled()) {
            int saved = 0;
            for (TaskHolder h : registry.values()) {
                ScheduledTask t = h.task();
                boolean shouldSave = t.status() != ScheduledTask.TaskStatus.COMPLETED
                    && t.status() != ScheduledTask.TaskStatus.FAILED
                    && (t.type() != ScheduledTask.TaskType.ONCE
                        || (t.executeAt() != null && t.executeAt().isAfter(Instant.now())));
                if (shouldSave) {
                    persistence.save(t);
                    saved++;
                }
            }
            System.out.println("[SCHEDULER] 持久化未完成任务：" + saved + " 个");
        }

        var executor = scheduler.getScheduledExecutor();
        executor.shutdown();
        try {
            boolean done = executor.awaitTermination(props.getAwaitTerminationSeconds(), TimeUnit.SECONDS);
            if (!done) {
                System.err.println("[SCHEDULER] 警告：有任务超时未完成，强制终止");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        registry.clear();
        System.out.println("[SCHEDULER] 关闭完成");
        callback.run();
    }

    public void close() {
        if (isRunning()) stop();
    }

    // ====================================
    // 对外：三种调度 API
    // ====================================

    public String scheduleOnce(
        String userId, String name, String message, Instant at, ZoneId tz
    ) {
        ScheduledTask task = ScheduledTask.once(userId, name, message, at, tz);
        Runnable runnable = buildRunnable(task);
        ScheduledFuture<?> future = scheduler.schedule(runnable, java.util.Date.from(at));
        registry.put(task.id(), new TaskHolder(future, task));
        markAndPersist(task);
        long delaySec = java.time.Duration.between(Instant.now(), at).getSeconds();
        System.out.printf("[SCHEDULER] ✅ 一次性任务已登记 id=%s  userId=%s  现在=%s  触发=%s  差=%ds  内容=%s%n",
            shortId(task.id()), userId,
            java.time.LocalTime.now().withNano(0),
            at.atZone(tz).toLocalTime().withNano(0),
            Math.max(0, delaySec), name);
        return task.id();
    }

    public String scheduleCron(
        String userId, String name, String message, String cronExpr, ZoneId tz
    ) {
        ScheduledTask task = ScheduledTask.cron(userId, name, message, cronExpr, tz);
        Runnable runnable = buildRunnable(task);
        CronTrigger trigger = new CronTrigger(cronExpr, tz);
        ScheduledFuture<?> future = scheduler.schedule(runnable, trigger);
        registry.put(task.id(), new TaskHolder(future, task));
        markAndPersist(task);
        System.out.printf("[SCHEDULER] ✅ Cron 任务已登记 id=%s  userId=%s  cron=%s  内容=%s%n",
            shortId(task.id()), userId, cronExpr, name);
        return task.id();
    }

    public String scheduleFixedDelay(
        String userId, String name, String message, Duration every, ZoneId tz
    ) {
        ScheduledTask task = ScheduledTask.fixedDelay(userId, name, message, every, tz);
        Runnable runnable = buildRunnable(task);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(runnable, every);
        registry.put(task.id(), new TaskHolder(future, task));
        markAndPersist(task);
        System.out.printf("[SCHEDULER] ✅ 循环任务已登记 id=%s  userId=%s  每=%s  内容=%s%n",
            shortId(task.id()), userId, every, name);
        return task.id();
    }

    // ====================================
    // 对外：任务管理 API
    // ====================================

    public List<ScheduledTask> listTasks(String userId) {
        List<ScheduledTask> result = new ArrayList<>();
        // 先收集 registry 里待执行/执行中的（真正还活着的）
        for (TaskHolder h : registry.values()) {
            ScheduledTask task = h.task();
            if (!userId.equals(task.userId())) continue;
            if (task.status() != ScheduledTask.TaskStatus.PENDING
                && task.status() != ScheduledTask.TaskStatus.RUNNING) continue;
            result.add(task);
        }
        // 再补持久化里可能没进 registry 的 PENDING 任务（比如刚启动还没恢复完的极端情况）
        if (props.isPersistenceEnabled()) {
            java.util.Set<String> already = new java.util.HashSet<>();
            for (ScheduledTask t : result) already.add(t.id());
            for (ScheduledTask t : persistence.findAllActive()) {
                if (!userId.equals(t.userId())) continue;
                if (already.contains(t.id())) continue;
                if (t.status() != ScheduledTask.TaskStatus.PENDING) continue;
                result.add(t);
            }
        }
        // 清理 registry 里的垃圾：完成/失败/取消——全踢，不区分一次性/周期
        List<String> staleIds = new ArrayList<>();
        for (var e : registry.entrySet()) {
            ScheduledTask task = e.getValue().task();
            boolean stale = switch (task.status()) {
                case COMPLETED, FAILED, CANCELLED -> true;  // 取消了不管啥类型都别占地方
                default -> false;
            };
            if (stale) staleIds.add(e.getKey());
        }
        if (!staleIds.isEmpty()) {
            System.out.printf("[SCHEDULER] 🧹 listTasks 清理 registry 里 %d 条历史垃圾（完成/失败/取消）%n", staleIds.size());
            staleIds.forEach(registry::remove);
        }

        result.sort(Comparator.comparing(t ->
            t.nextFireTime() != null ? t.nextFireTime() : Instant.MAX
        ));
        return result;
    }

    public Optional<ScheduledTask> getTask(String taskId) {
        TaskHolder h = registry.get(taskId);
        return h == null ? persistence.findById(taskId) : Optional.of(h.task());
    }

    public record CancelResult(boolean success, String reason, ScheduledTask task) {}

    public CancelResult cancelTask(String taskId) {
        TaskHolder h = registry.get(taskId);
        if (h == null) {
            Optional<ScheduledTask> t = persistence.findById(taskId);
            if (t.isEmpty()) return new CancelResult(false, "任务不存在", null);
            ScheduledTask task = t.get();
            if (task.status() == ScheduledTask.TaskStatus.CANCELLED
                || task.status() == ScheduledTask.TaskStatus.COMPLETED
                || task.status() == ScheduledTask.TaskStatus.FAILED) {
                return new CancelResult(false,
                    "任务已" + zhStatus(task.status()), task);
            }
            markAndPersist(task.withStatus(ScheduledTask.TaskStatus.CANCELLED));
            return new CancelResult(true, null, task);
        }
        ScheduledTask task = h.task();
        if (task.status() == ScheduledTask.TaskStatus.RUNNING) {
            return new CancelResult(false, "任务正在执行中，稍等一下再取消", task);
        }
        if (task.status() == ScheduledTask.TaskStatus.COMPLETED
            || task.status() == ScheduledTask.TaskStatus.FAILED
            || task.status() == ScheduledTask.TaskStatus.CANCELLED) {
            return new CancelResult(false,
                "任务已" + zhStatus(task.status()), task);
        }
        boolean ok = h.future().cancel(false);
        if (ok) {
            ScheduledTask cancelled = task.withStatus(ScheduledTask.TaskStatus.CANCELLED);
            // ⚠️ 取消成功立刻从内存 registry 移除，下次 /tasks /我的任务 就不会再出现了
            registry.remove(taskId);
            markAndPersist(cancelled);
            System.out.printf("[SCHEDULER] ✅ 取消成功并移出 registry id=%s  内容=%s%n",
                shortId(taskId), cancelled.name());
            return new CancelResult(true, null, cancelled);
        }
        return new CancelResult(false, "取消失败，任务状态已变更", task);
    }

    public record CancelAllResult(int success, int failed, int running, int skipped) {}

    public CancelAllResult cancelAll(String userId) {
        int success = 0, failed = 0, running = 0, skipped = 0;
        for (ScheduledTask t : listTasks(userId)) {
            CancelResult r = cancelTask(t.id());
            if (r.success) success++;
            else if (r.reason != null && r.reason.contains("执行中")) running++;
            else if (r.reason != null && r.reason.startsWith("任务已")) skipped++;
            else failed++;
        }
        System.out.printf("[SCHEDULER] 批量取消结果 userId=%s  success=%d  running=%d  skipped=%d  failed=%d%n",
            userId, success, running, skipped, failed);
        return new CancelAllResult(success, failed, running, skipped);
    }

    // ====================================
    // 内部：任务执行 + 重试
    // ====================================

    private Runnable buildRunnable(ScheduledTask initialTask) {
        return () -> {
            String id = initialTask.id();
            TaskHolder holder = registry.get(id);
            if (holder == null) return;
            ScheduledTask current = holder.task();
            if (current.status() == ScheduledTask.TaskStatus.CANCELLED) return;

            System.out.printf("[SCHEDULER] ⏰ 触发执行 id=%s  内容=%s  现在=%s%n",
                shortId(id), current.name(), java.time.LocalTime.now().withNano(0));

            updateRegistry(id, current.withStatus(ScheduledTask.TaskStatus.RUNNING));
            Exception lastError = null;
            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                try {
                    // ⚠️ 不再硬编码 sendText！业务逻辑完全搬到 TaskPayload.execute() 里
                    //   TEXT_REMIND = 发「⏰ 提醒：xxx」（原来的逻辑）
                    //   以后 BILI_ANIME = 调 B 站 API + 发 B 站卡片/链接（新增 Payload 就能实现）
                    TaskPayload payload = TaskPayloadFactory.from(current);
                    payload.execute(sender, current.userId());
                    System.out.printf("[SCHEDULER] ✅ 发送成功 id=%s  目标=%s  尝试次数=%d  payloadType=%s%n",
                        shortId(id), current.userId(), attempt + 1, payload.getType());
                    if (current.type() == ScheduledTask.TaskType.ONCE) {
                        updateRegistry(id, current
                            .withStatus(ScheduledTask.TaskStatus.COMPLETED)
                            .withRetryCount(attempt));
                        persistence.delete(id);
                        registry.remove(id);
                    } else {
                        updateRegistry(id, current
                            .withStatus(ScheduledTask.TaskStatus.PENDING)
                            .withRetryCount(attempt));
                        markAndPersist(registry.get(id).task());
                        System.out.printf("[SCHEDULER] 🔁 周期任务当次完成 id=%s  下一次继续排队  内容=%s%n",
                            shortId(id), current.name());
                    }
                    return;
                } catch (Exception e) {
                    lastError = e;
                    System.err.printf("[SCHEDULER] ❌ 任务 %s 执行失败(%d/%d): %s  目标=%s%n",
                        current.name(), attempt + 1, MAX_RETRY, e.getMessage(), current.userId());
                    e.printStackTrace();
                    if (attempt + 1 < MAX_RETRY) {
                        try {
                            Thread.sleep(RETRY_DELAYS_SEC[attempt] * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            // ---- 3 次重试全部失败的分支 ----
            if (current.type() == ScheduledTask.TaskType.ONCE) {
                ScheduledTask failed = current
                    .withStatus(ScheduledTask.TaskStatus.FAILED)
                    .withRetryCount(MAX_RETRY);
                updateRegistry(id, failed);
                persistence.delete(id);
                registry.remove(id);
            } else {
                // 周期任务：当次失败就算了，状态改回 PENDING 继续等下一次（别把周期任务废掉）
                updateRegistry(id, current
                    .withStatus(ScheduledTask.TaskStatus.PENDING)
                    .withRetryCount(0));
                markAndPersist(registry.get(id).task());
                System.out.printf("[SCHEDULER] 🔁 周期任务当次失败 3 次，放弃本次继续排队 id=%s%n", shortId(id));
            }
            notifications.notifyError("定时任务失败/" + current.name(), lastError);
            try {
                sender.sendText(current.userId(),
                    "⚠️ 提醒任务「" + current.name() + "」本次发送失败，已重试 3 次，" +
                    (current.type() == ScheduledTask.TaskType.ONCE
                        ? "任务已结束不再重试。"
                        : "下次到点会再尝试发送。"));
            } catch (Exception ignored) {}
        };
    }

    private void reSchedule(ScheduledTask t) {
        Runnable runnable = buildRunnable(t);
        ScheduledFuture<?> future = switch (t.type()) {
            case ONCE -> scheduler.schedule(runnable, java.util.Date.from(t.executeAt()));
            case CRON -> scheduler.schedule(runnable,
                new CronTrigger(t.cronExpression(), t.timezone()));
            case FIXED_DELAY -> scheduler.scheduleWithFixedDelay(runnable, t.fixedDelay());
        };
        registry.put(t.id(), new TaskHolder(future, t));
        System.out.printf("[SCHEDULER] ↻ 恢复任务 id=%s  userId=%s  type=%s  内容=%s%n",
            shortId(t.id()), t.userId(), t.type(), t.name());
    }

    // ====================================
    // 辅助
    // ====================================

    private void updateRegistry(String id, ScheduledTask newTask) {
        TaskHolder h = registry.get(id);
        if (h != null) {
            registry.put(id, new TaskHolder(h.future(), newTask));
            markAndPersist(newTask);
        }
    }

    private void markAndPersist(ScheduledTask t) {
        if (!props.isPersistenceEnabled()) return;
        if (t.type() == ScheduledTask.TaskType.ONCE
            && (t.status() == ScheduledTask.TaskStatus.COMPLETED
                || t.status() == ScheduledTask.TaskStatus.FAILED)) {
            persistence.delete(t.id());
        } else {
            persistence.save(t);
        }
    }

    private static String zhStatus(ScheduledTask.TaskStatus s) {
        return switch (s) {
            case PENDING -> "待执行";
            case RUNNING -> "执行中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case FAILED -> "失败";
        };
    }

    private static String shortId(String id) {
        return id == null || id.length() < 8 ? id : id.substring(0, 8);
    }
}