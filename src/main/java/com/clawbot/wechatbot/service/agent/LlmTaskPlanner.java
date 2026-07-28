package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 使用一次轻量模型调用生成外循环需要的结构化任务计划。 */
public final class LlmTaskPlanner implements TaskPlanner {
    private static final String PLANNER_PROMPT = """
        你是 Agent 任务规划器，只输出严格 JSON，不回答用户问题。
        输出格式：
        {"tasks":[
          {"id":"t1","type":"CHAT_TOOL","instruction":"完整任务描述","depends_on":[]},
          {"id":"t2","type":"IMAGE_GENERATION","instruction":"完整图片描述","depends_on":["t1"]}
        ]}

        任务类型：
        - CHAT_TOOL：普通问答，以及需要天气、汇率、新闻、网页、时间等 function-calling 工具的任务。
        - IMAGE_GENERATION：画图、生成图片、来一张图片等文生图任务。

        规则：
        1. 找出所有明确需求，保持原始顺序，不得遗漏，也不得增加用户没提出的任务。
        2. 每个任务必须自包含，保留地点、日期、金额、格式和风格等约束。
        3. 同一查询的并列参数不要误拆，例如“人民币和日元汇率”是一个 CHAT_TOOL。
        4. 相互独立的任务 depends_on 为空，可以并行。
        5. 如果后一个任务必须使用前一个任务的结果，将前一个任务 id 写入 depends_on。
        6. “根据杭州天气生成图片”应拆成天气 CHAT_TOOL 和依赖天气结果的 IMAGE_GENERATION。
        7. 单一需求也必须输出一个结构化任务。
        """;

    private final DeepSeekClient client;
    private final ObjectMapper mapper;
    private final int maxTasks;

    public LlmTaskPlanner(DeepSeekClient client, int maxTasks) {
        this.client = client;
        this.mapper = client.mapper();
        this.maxTasks = Math.max(1, maxTasks);
    }

    @Override
    public List<AgentTask> plan(String userText) throws Exception {
        if (userText == null || userText.isBlank()) return List.of();

        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", PLANNER_PROMPT));
        messages.add(message("user", userText.trim()));
        JsonNode response = client.chat(messages, mapper.createArrayNode(), 0.0);
        String content = response.path("choices").path(0).path("message")
            .path("content").asText("");
        return parseTasks(userText.trim(), content);
    }

    List<AgentTask> parseTasks(String originalText, String modelContent) throws Exception {
        JsonNode tasksNode = mapper.readTree(extractJson(modelContent)).path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            return List.of(AgentTask.chat(originalText));
        }
        if (tasksNode.size() > maxTasks) {
            return List.of(AgentTask.chat(originalText));
        }

        List<RawTask> rawTasks = new ArrayList<>();
        Set<String> instructions = new LinkedHashSet<>();
        int rawIndex = 0;
        for (JsonNode node : tasksNode) {
            String instruction;
            String rawId;
            AgentTaskType type;
            List<String> dependencies = new ArrayList<>();
            if (node.isTextual()) {
                instruction = node.asText("").trim();
                rawId = "t" + (rawIndex + 1);
                type = AgentTaskType.CHAT_TOOL;
            } else {
                instruction = node.path("instruction").asText("").trim();
                rawId = node.path("id").asText("t" + (rawIndex + 1)).trim();
                type = parseType(node.path("type").asText(""));
                JsonNode dependencyNode = node.path("depends_on");
                if (dependencyNode.isArray()) {
                    dependencyNode.forEach(item -> {
                        if (item.isTextual() && !item.asText().isBlank()) {
                            dependencies.add(item.asText().trim());
                        }
                    });
                }
            }
            rawIndex++;
            if (instruction.isEmpty() || instruction.length() > 2000
                || !instructions.add(instruction)) {
                continue;
            }
            rawTasks.add(new RawTask(rawId, type, instruction, dependencies));
        }
        if (rawTasks.isEmpty() || rawTasks.size() > maxTasks) {
            return List.of(AgentTask.chat(originalText));
        }

        Map<String, String> canonicalIds = new LinkedHashMap<>();
        for (int index = 0; index < rawTasks.size(); index++) {
            canonicalIds.putIfAbsent(rawTasks.get(index).rawId(), "task-" + (index + 1));
        }

        List<AgentTask> tasks = new ArrayList<>();
        for (int index = 0; index < rawTasks.size(); index++) {
            RawTask raw = rawTasks.get(index);
            String id = "task-" + (index + 1);
            List<String> dependencies = raw.dependencies().stream()
                .map(canonicalIds::get)
                .filter(java.util.Objects::nonNull)
                .filter(dependency -> !dependency.equals(id))
                .distinct()
                .toList();
            tasks.add(new AgentTask(
                id, index, raw.type(), raw.instruction(), dependencies));
        }
        return List.copyOf(tasks);
    }

    private AgentTaskType parseType(String value) {
        String normalized = value == null
            ? ""
            : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("IMAGE")
            || normalized.equals("IMAGE_GEN")
            || normalized.equals("IMAGE_GENERATION")) {
            return AgentTaskType.IMAGE_GENERATION;
        }
        return AgentTaskType.CHAT_TOOL;
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

    private record RawTask(
        String rawId,
        AgentTaskType type,
        String instruction,
        List<String> dependencies
    ) {
    }
}
