package com.clawbot.wechatbot.scheduler;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

@Document(collection = "agent_scheduled_tasks")
public class MongoScheduledTask {

    @Id
    private String id;
    private String userId;
    private String taskType;
    private String name;
    private String message;
    private Instant executeAt;
    private String cronExpression;
    private Long fixedDelayMillis;
    private String timezone;
    private Instant nextFireTime;
    private String status;
    private Integer retryCount;
    private Instant createdAt;
    private Instant updatedAt;
    // 新增：Payload 体系（解耦「调度元数据」和「业务内容」）——老数据这两列是 null，工厂会自动兜底成 TextRemindPayload
    private String payloadType;
    private String payloadJson;

    public MongoScheduledTask() {}

    public static MongoScheduledTask fromRecord(ScheduledTask t) {
        MongoScheduledTask m = new MongoScheduledTask();
        m.setId(t.id());
        m.setUserId(t.userId());
        m.setTaskType(t.type().name());
        m.setName(t.name());
        m.setMessage(t.message());
        m.setExecuteAt(t.executeAt());
        m.setCronExpression(t.cronExpression());
        m.setFixedDelayMillis(t.fixedDelay() == null ? null : t.fixedDelay().toMillis());
        m.setTimezone(t.timezone() == null ? null : t.timezone().getId());
        m.setNextFireTime(t.nextFireTime());
        m.setStatus(t.status().name());
        m.setRetryCount(t.retryCount());
        m.setCreatedAt(t.createdAt());
        m.setUpdatedAt(t.updatedAt());
        m.setPayloadType(t.payloadType());
        m.setPayloadJson(t.payloadJson());
        return m;
    }

    public ScheduledTask toRecord() {
        ScheduledTask.TaskType type = ScheduledTask.TaskType.valueOf(taskType);
        Duration delay = fixedDelayMillis == null ? null : Duration.ofMillis(fixedDelayMillis);
        ZoneId tz = timezone == null ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        return new ScheduledTask(
            id, userId, type, name, message,
            executeAt, cronExpression, delay, tz, nextFireTime,
            ScheduledTask.TaskStatus.valueOf(status),
            retryCount == null ? 0 : retryCount,
            createdAt, updatedAt,
            payloadType, payloadJson   // 老数据这俩是 null，TaskPayloadFactory 会自动兜底
        );
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getExecuteAt() { return executeAt; }
    public void setExecuteAt(Instant executeAt) { this.executeAt = executeAt; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public Long getFixedDelayMillis() { return fixedDelayMillis; }
    public void setFixedDelayMillis(Long millis) { this.fixedDelayMillis = millis; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Instant getNextFireTime() { return nextFireTime; }
    public void setNextFireTime(Instant next) { this.nextFireTime = next; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer count) { this.retryCount = count; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant at) { this.createdAt = at; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant at) { this.updatedAt = at; }

    public String getPayloadType() { return payloadType; }
    public void setPayloadType(String payloadType) { this.payloadType = payloadType; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}