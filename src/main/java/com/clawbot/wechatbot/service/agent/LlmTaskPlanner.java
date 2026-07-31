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
          {"id":"t2","type":"IMAGE_UNDERSTANDING","instruction":"分析用户上传的图片","depends_on":[]},
          {"id":"t3","type":"IMAGE_GENERATION","instruction":"完整图片描述","depends_on":["t2"]}
        ]}

        任务类型：
        - CHAT_TOOL：普通问答，以及需要天气、汇率、新闻、网页、时间等 function-calling 工具的任务。
        - IMAGE_UNDERSTANDING：描述、识别、分析用户上传的图片，或回答与上传图片有关的问题。
        - DOCUMENT_ANALYSIS：读取、总结、分析用户上传的 PDF、Word 或 TXT 文档。
        - IMAGE_GENERATION：画图、生成图片、来一张图片等文生图任务。

        规则：
        1. 找出所有明确需求，保持原始顺序，不得遗漏，也不得增加用户没提出的任务。
        2. 每个任务必须自包含，保留地点、日期、金额、格式和风格等约束。
        3. 同一查询的并列参数不要误拆，例如“人民币和日元汇率”是一个 CHAT_TOOL。
        4. 相互独立的任务 depends_on 为空，可以并行。
        5. 如果后一个任务必须使用前一个任务的结果，将前一个任务 id 写入 depends_on。
        6. “根据杭州天气生成图片”应拆成天气 CHAT_TOOL 和依赖天气结果的 IMAGE_GENERATION。
        7. 多个操作即使都属于 CHAT_TOOL 也必须拆开。例如“订阅牧神记，然后设置电影推送时间20:00”
           必须拆成“订阅牧神记”和“设置电影推送时间20:00”两个独立 CHAT_TOOL。
        8. B站的订阅、搜索、推荐、标记、推送设置都属于 CHAT_TOOL，由后续 function-calling 执行。
        9. 单一需求也必须输出一个结构化任务。
        10. 输入中“【附件】”只描述用户实际上传的附件，不是用户要求。涉及上传图片或文档时，
            必须使用对应的 IMAGE_UNDERSTANDING 或 DOCUMENT_ANALYSIS，不能用 CHAT_TOOL 假装读取附件。
        11. “分析上传图片并根据图片生成新图”应让 IMAGE_GENERATION 依赖 IMAGE_UNDERSTANDING。
        12. “总结上传文档并根据总结执行其他操作”应让后续任务依赖 DOCUMENT_ANALYSIS。
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
        return planDetailed(userText).tasks();
    }

    @Override
    public TaskPlan planDetailed(String userText) throws Exception {
        if (userText == null || userText.isBlank()) {
            return TaskPlan.accepted(List.of(), maxTasks);
        }

        ArrayNode messages = mapper.createArrayNode();
        messages.add(message(
            "system",
            PLANNER_PROMPT
                + "\n12. 系统单次安全上限为 " + maxTasks
                + " 项任务。如果实际需求超过上限，仍须在 tasks 中完整列出所有任务，"
                + "由系统统一拒绝；不得为了满足上限合并或遗漏任务。"));
        messages.add(message("user", userText.trim()));
        JsonNode response = client.chat(messages, mapper.createArrayNode(), 0.0);
        String content = response.path("choices").path(0).path("message")
            .path("content").asText("");
        return parsePlan(userText.trim(), content);
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }

    List<AgentTask> parseTasks(String originalText, String modelContent) throws Exception {
        return parsePlan(originalText, modelContent).tasks();
    }

    TaskPlan parsePlan(String originalText, String modelContent) throws Exception {
        JsonNode tasksNode = mapper.readTree(extractJson(modelContent)).path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            return TaskPlan.accepted(
                List.of(AgentTask.chat(originalText)), maxTasks);
        }
        if (tasksNode.size() > maxTasks) {
            return TaskPlan.limitExceeded(tasksNode.size(), maxTasks);
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
            if (rawTasks.size() > maxTasks) {
                return TaskPlan.limitExceeded(rawTasks.size(), maxTasks);
            }
            return TaskPlan.accepted(
                List.of(AgentTask.chat(originalText)), maxTasks);
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
        return TaskPlan.accepted(List.copyOf(tasks), maxTasks);
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
        if (normalized.equals("VISION")
            || normalized.equals("IMAGE_ANALYSIS")
            || normalized.equals("IMAGE_UNDERSTANDING")) {
            return AgentTaskType.IMAGE_UNDERSTANDING;
        }
        if (normalized.equals("DOCUMENT")
            || normalized.equals("DOCUMENT_SUMMARY")
            || normalized.equals("DOCUMENT_ANALYSIS")) {
            return AgentTaskType.DOCUMENT_ANALYSIS;
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
