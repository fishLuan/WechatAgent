package com.clawbot.wechatbot.scheduler.task.impl;

import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.clawbot.wechatbot.scheduler.task.ScheduledTaskContentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OneTimeReminderContentProvider implements ScheduledTaskContentProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TEXT = "⏰ 这是你预约的单次提醒～";

    @Override
    public TaskType taskType() { return TaskType.ONE_TIME_REMINDER; }

    @Override
    public String provideContent(String userId, String paramsJson) {
        String custom = null;
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                JsonNode node = MAPPER.readTree(paramsJson);
                if (node != null && node.isObject()) {
                    custom = node.path("message_content").asText(null);
                }
            }
        } catch (Exception ignored) {}
        return (custom != null && !custom.isBlank()) ? custom : DEFAULT_TEXT;
    }
}