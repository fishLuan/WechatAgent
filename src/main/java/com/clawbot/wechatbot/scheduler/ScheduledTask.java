package com.clawbot.wechatbot.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

public record ScheduledTask(
    String id,
    String userId,
    TaskType type,
    String name,
    String message,
    Instant executeAt,
    String cronExpression,
    Duration fixedDelay,
    ZoneId timezone,
    Instant nextFireTime,
    TaskStatus status,
    int retryCount,
    Instant createdAt,
    Instant updatedAt,
    // 新增：Payload 体系——彻底把「调度元数据」和「业务内容」分开
    // payloadType = 用什么 Payload 类执行（TEXT_REMIND / BILI_ANIME / ... 后续加任意类型）
    // payloadJson = 该 Payload 序列化后的 JSON，还原时用
    // name/message 字段保留不变：1) 向后兼容老数据  2) 给「我的任务」列表做展示快照
    String payloadType,
    String payloadJson
) {

    public enum TaskType { ONCE, CRON, FIXED_DELAY }
    public enum TaskStatus { PENDING, RUNNING, COMPLETED, CANCELLED, FAILED }

    public static ScheduledTask once(
        String userId, String name, String message, Instant at, ZoneId tz
    ) {
        TaskPayload payload = new TextRemindPayload(message);
        return once(userId, name, at, tz, payload);
    }

    public static ScheduledTask once(
        String userId, String displayName, Instant at, ZoneId tz, TaskPayload payload
    ) {
        Instant now = Instant.now();
        String snapName = (displayName == null || displayName.isBlank())
            ? payload.getDisplayName() : displayName;
        String snapMsg = payload instanceof TextRemindPayload t ? t.getMessage() : snapName;
        return new ScheduledTask(
            UUID.randomUUID().toString(),
            userId, TaskType.ONCE, snapName, snapMsg,
            at, null, null, tz, at,
            TaskStatus.PENDING, 0, now, now,
            payload.getType(), payload.toJson()
        );
    }

    public static ScheduledTask cron(
        String userId, String name, String message, String expr, ZoneId tz
    ) {
        TaskPayload payload = new TextRemindPayload(message);
        return cron(userId, name, expr, tz, payload);
    }

    public static ScheduledTask cron(
        String userId, String displayName, String expr, ZoneId tz, TaskPayload payload
    ) {
        Instant now = Instant.now();
        String snapName = (displayName == null || displayName.isBlank())
            ? payload.getDisplayName() : displayName;
        String snapMsg = payload instanceof TextRemindPayload t ? t.getMessage() : snapName;
        return new ScheduledTask(
            UUID.randomUUID().toString(),
            userId, TaskType.CRON, snapName, snapMsg,
            null, expr, null, tz, null,
            TaskStatus.PENDING, 0, now, now,
            payload.getType(), payload.toJson()
        );
    }

    public static ScheduledTask fixedDelay(
        String userId, String name, String message, Duration every, ZoneId tz
    ) {
        TaskPayload payload = new TextRemindPayload(message);
        return fixedDelay(userId, name, every, tz, payload);
    }

    public static ScheduledTask fixedDelay(
        String userId, String displayName, Duration every, ZoneId tz, TaskPayload payload
    ) {
        Instant now = Instant.now();
        String snapName = (displayName == null || displayName.isBlank())
            ? payload.getDisplayName() : displayName;
        String snapMsg = payload instanceof TextRemindPayload t ? t.getMessage() : snapName;
        return new ScheduledTask(
            UUID.randomUUID().toString(),
            userId, TaskType.FIXED_DELAY, snapName, snapMsg,
            null, null, every, tz, now.plus(every),
            TaskStatus.PENDING, 0, now, now,
            payload.getType(), payload.toJson()
        );
    }

    public ScheduledTask withStatus(TaskStatus s) {
        return new ScheduledTask(id, userId, type, name, message, executeAt,
            cronExpression, fixedDelay, timezone, nextFireTime, s,
            retryCount, createdAt, Instant.now(), payloadType, payloadJson);
    }

    public ScheduledTask withNextFireTime(Instant next) {
        return new ScheduledTask(id, userId, type, name, message, executeAt,
            cronExpression, fixedDelay, timezone, next, status,
            retryCount, createdAt, Instant.now(), payloadType, payloadJson);
    }

    public ScheduledTask withRetryCount(int count) {
        return new ScheduledTask(id, userId, type, name, message, executeAt,
            cronExpression, fixedDelay, timezone, nextFireTime, status,
            count, createdAt, Instant.now(), payloadType, payloadJson);
    }

    public String shortDescription() {
        String typeIcon = switch (type) {
            case ONCE -> "⏰";
            case CRON -> "🔁";
            case FIXED_DELAY -> "🔁";
        };
        String when = switch (type) {
            case ONCE -> nextFireTime == null ? "已过期" : formatNextFire(nextFireTime, timezone);
            case CRON -> com.clawbot.wechatbot.scheduler.TaskCommandRouter.humanizeCronStatic(cronExpression);
            case FIXED_DELAY -> "每 " + formatDuration(fixedDelay);
        };
        return typeIcon + " " + name + " · " + when;
    }

    private static String formatNextFire(Instant instant, ZoneId tz) {
        var zdt = instant.atZone(tz);
        return String.format("%02d-%02d %02d:%02d",
            zdt.getMonthValue(), zdt.getDayOfMonth(),
            zdt.getHour(), zdt.getMinute());
    }

    private static String formatDuration(Duration d) {
        long totalMinutes = d.toMinutes();
        if (totalMinutes < 60) return totalMinutes + "分钟";
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours < 24) return hours + "小时" + (minutes > 0 ? minutes + "分钟" : "");
        long days = hours / 24;
        long restHours = hours % 24;
        return days + "天" + (restHours > 0 ? restHours + "小时" : "");
    }
}