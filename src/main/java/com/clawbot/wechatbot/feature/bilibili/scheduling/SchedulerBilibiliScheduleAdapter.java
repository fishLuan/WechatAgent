package com.clawbot.wechatbot.feature.bilibili.scheduling;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;

/** 将B站调度请求适配到项目统一的调度核心。 */
@Component
public final class SchedulerBilibiliScheduleAdapter implements BilibiliSchedulePort {
    private final SchedulerControlService schedules;
    private final ObjectMapper mapper;

    public SchedulerBilibiliScheduleAdapter(
        SchedulerControlService schedules, ObjectMapper mapper
    ) {
        this.schedules = schedules;
        this.mapper = mapper;
    }

    @Override
    public void scheduleOneTime(
        String userId, ContentType type, int count, Instant fireAt
    ) {
        ScheduledSubscription task = baseTask(userId, type, count);
        task.setCronExpression("");
        ObjectNode params = params(type, count);
        params.put("fire_timestamp", fireAt.toEpochMilli());
        params.put("already_fired", false);
        task.setParamsJson(params.toString());
        schedules.createOrUpdate(task);
    }

    @Override
    public void scheduleDaily(
        String userId, ContentType type, int count, LocalTime pushTime
    ) {
        cancelExisting(userId, type);
        ScheduledSubscription task = baseTask(userId, type, count);
        task.setCronExpression(SchedulerControlService.timeToDailyCron(
            pushTime.toString()));
        task.setParamsJson(params(type, count).toString());
        schedules.createOrUpdate(task);
    }

    private ScheduledSubscription baseTask(
        String userId, ContentType type, int count
    ) {
        ScheduledSubscription task = new ScheduledSubscription();
        task.setUserId(userId);
        task.setTaskType(TaskType.BILIBILI_PUSH);
        task.setEnabled(true);
        return task;
    }

    private ObjectNode params(ContentType type, int count) {
        ObjectNode params = mapper.createObjectNode();
        params.put("content_type", type.name());
        params.put("count", Math.max(1, count));
        return params;
    }

    private void cancelExisting(String userId, ContentType type) {
        for (ScheduledSubscription task : schedules.listByUser(userId)) {
            if (task.isEnabled()
                && task.getTaskType() == TaskType.BILIBILI_PUSH
                && hasContentType(task, type)) {
                schedules.cancelBySubscriptionId(task.getId(), userId);
            }
        }
    }

    private boolean hasContentType(
        ScheduledSubscription task, ContentType expected
    ) {
        try {
            return expected.name().equalsIgnoreCase(
                mapper.readTree(task.getParamsJson())
                    .path("content_type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
