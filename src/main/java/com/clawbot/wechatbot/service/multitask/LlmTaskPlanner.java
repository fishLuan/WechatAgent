package com.clawbot.wechatbot.service.multitask;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Uses a lightweight model pass to identify every independent requirement.
 * It never executes tools; tool selection remains in the normal chat service.
 */
public final class LlmTaskPlanner implements TaskPlanner {
    private static final String PLANNER_PROMPT = """
        你是任务拆解器，只输出严格 JSON，不回答用户问题。
        输出格式：{"tasks":["任务1","任务2"]}

        规则：
        1. 找出用户消息中的所有明确需求，保持原始顺序，不得遗漏。
        2. 只拆分可以独立回答的需求；有前后依赖的步骤合并成一个任务。
        3. 条件、格式、语气、时间、地点、金额等约束必须复制到对应任务中。
        4. 不要把同一问题中的并列参数误拆成多个任务，例如“人民币和日元汇率”是一个任务。
        5. 单一需求只返回一个任务；不要增加用户未提出的任务。
        6. 每个任务必须自包含，避免使用“它、这个、上面”等指代。
        """;

    private final DeepSeekClient client;
    private final ObjectMapper mapper;
    private final int maxTasks;

    public LlmTaskPlanner(DeepSeekClient client, int maxTasks) {
        this.client = client;
        this.mapper = client.mapper();
        this.maxTasks = Math.max(2, maxTasks);
    }

    @Override
    public List<String> plan(String userText) throws Exception {
        if (userText == null || userText.isBlank()) return List.of();

        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", PLANNER_PROMPT));
        messages.add(message("user", userText.trim()));
        JsonNode response = client.chat(messages, mapper.createArrayNode(), 0.0);
        String content = response.path("choices").path(0).path("message")
            .path("content").asText("");
        return parseTasks(userText.trim(), content);
    }

    List<String> parseTasks(String originalText, String modelContent) throws Exception {
        String json = extractJson(modelContent);
        JsonNode tasksNode = mapper.readTree(json).path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) return List.of(originalText);

        Set<String> uniqueTasks = new LinkedHashSet<>();
        for (JsonNode item : tasksNode) {
            if (!item.isTextual()) continue;
            String task = item.asText().trim();
            if (!task.isEmpty() && task.length() <= 2000) uniqueTasks.add(task);
        }
        if (uniqueTasks.size() <= 1 || uniqueTasks.size() > maxTasks) {
            return List.of(originalText);
        }
        return List.copyOf(new ArrayList<>(uniqueTasks));
    }

    private String extractJson(String content) {
        if (content == null) return "{}";
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) return "{}";
        return content.substring(start, end + 1);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }
}
