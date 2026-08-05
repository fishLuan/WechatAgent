package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.clawbot.wechatbot.service.agent.reference.ResultReference;
import com.clawbot.wechatbot.service.agent.reference.ReferenceResolutionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Uses one lightweight LLM call to create the outer-loop task graph. */
public final class LlmTaskPlanner implements TaskPlanner {
    private static final String PROMPT = """
        你是 Agent 任务规划器，只输出严格 JSON，不回答用户问题。
        格式：
        {"tasks":[{"id":"t1","type":"CHAT_TOOL","skill_name":"","instruction":"完整任务","input":{},"expected_output":{},"acceptance_criteria":[{"description":"验收说明","path":"$.字段","operator":"EQUALS","expected":"期望值","required":true}],"depends_on":[]}]}

        固定任务类型：
        - CHAT_TOOL：普通问答以及 function-calling 工具。
        - SKILL：下方动态技能目录覆盖的领域任务；skill_name 必须填写目录中的 name。
        - IMAGE_UNDERSTANDING：分析用户上传的图片。
        - DOCUMENT_ANALYSIS：读取或分析用户上传的文档。
        - IMAGE_GENERATION：生成图片。

        规则：
        1. 找出所有明确需求并保持顺序，不遗漏、不增加。
        2. 每个任务必须自包含，保留地点、日期、金额、格式和风格等约束。
        3. 独立任务 depends_on 为空；后续任务需要前一结果时填写前一任务 id。
        4. 同一技能中的多个独立操作也必须拆开。
        5. 动态技能目录覆盖的操作必须使用 SKILL，不得用 CHAT_TOOL 或联网搜索替代。
        6. 不得编造 skill_name；目录中没有合适技能时使用 CHAT_TOOL。
        7. 附件必须使用对应的图片理解或文档分析任务。
        8. 单一需求也必须输出一个结构化任务。
        9. 用户要求先创作、查询或整理内容，再生成文档时，拆成内容任务和
           document-generation SKILL，文档任务依赖内容任务。
        10. 用户要求把回答、查询结果或创作内容转成语音时，拆成内容任务和
            voice-reply SKILL，语音任务依赖内容任务。
        11. 如果用户已经在冒号后提供了完整正文，可只创建对应的文档或语音
            SKILL，并将完整正文保留在 instruction 中。
        12. input 只填写用户已经明确提供或能确定的结构化参数，不得猜测未知值。
        13. expected_output 描述后续步骤真正需要的输出字段及含义；没有要求时使用 {}。
        14. acceptance_criteria 给出可机器检查的关键验收条件；path 必须以 $ 开头。
            operator 只能是 EXISTS、NOT_EMPTY、EQUALS、NOT_EQUALS、CONTAINS、
            GREATER_THAN、GREATER_THAN_OR_EQUALS、LESS_THAN、LESS_THAN_OR_EQUALS、
            MATCHES_REGEX、TYPE_IS。无需 expected 的操作符可以省略 expected。
        15. 地点、日期、时间、币种、作品名称、数量和文件格式等用户硬性约束，
            必须同时保留在 input 或 acceptance_criteria 中。
        16. 后续任务需要使用前置任务的精确字段时，input 必须使用
            {"$ref":"前置任务id.output.字段路径"}，并在 depends_on 声明该任务；
            禁止把未知的 ID、日期、金额等值重新猜写到 input。
        """;

    private static final SkillCatalog EMPTY_CATALOG = new SkillCatalog() {
        @Override
        public List<SkillDefinition> definitions() {
            return List.of();
        }

        @Override
        public boolean contains(String name) {
            return false;
        }
    };

    private final DeepSeekClient client;
    private final ObjectMapper mapper;
    private final int maxTasks;
    private final SkillCatalog skillCatalog;

    public LlmTaskPlanner(DeepSeekClient client, int maxTasks) {
        this(client, maxTasks, EMPTY_CATALOG);
    }

    public LlmTaskPlanner(
        DeepSeekClient client,
        int maxTasks,
        SkillCatalog skillCatalog
    ) {
        this.client = client;
        this.mapper = client.mapper();
        this.maxTasks = Math.max(1, maxTasks);
        this.skillCatalog = skillCatalog == null ? EMPTY_CATALOG : skillCatalog;
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
        messages.add(message("system", PROMPT
            + "\n动态技能目录：\n" + skillCatalog.plannerCatalog()
            + "\n系统单次安全上限为 " + maxTasks
            + " 项任务。若实际需求超过上限，仍须完整列出，由系统统一拒绝。"));
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

    List<AgentTask> parseTasks(String originalText, String modelContent)
        throws Exception {
        return parsePlan(originalText, modelContent).tasks();
    }

    TaskPlan parsePlan(String originalText, String modelContent) throws Exception {
        JsonNode tasksNode = mapper.readTree(extractJson(modelContent)).path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            return TaskPlan.accepted(List.of(AgentTask.chat(originalText)), maxTasks);
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
            String skillName = "";
            AgentTaskType type;
            JsonNode input = mapper.createObjectNode();
            JsonNode expectedOutput = mapper.createObjectNode();
            List<AcceptanceCriterion> acceptanceCriteria = new ArrayList<>();
            List<String> dependencies = new ArrayList<>();
            if (node.isTextual()) {
                instruction = node.asText("").trim();
                rawId = "t" + (rawIndex + 1);
                type = AgentTaskType.CHAT_TOOL;
            } else {
                instruction = node.path("instruction").asText("").trim();
                rawId = node.path("id").asText("t" + (rawIndex + 1)).trim();
                type = parseType(node.path("type").asText(""));
                skillName = node.path("skill_name").asText("").trim();
                if (node.path("input").isObject()) {
                    input = node.path("input").deepCopy();
                }
                if (node.path("expected_output").isObject()) {
                    expectedOutput = node.path("expected_output").deepCopy();
                }
                parseAcceptanceCriteria(node.path("acceptance_criteria"))
                    .forEach(acceptanceCriteria::add);
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
            if (type == AgentTaskType.SKILL
                && (skillName.isBlank() || !skillCatalog.contains(skillName))) {
                type = AgentTaskType.CHAT_TOOL;
                skillName = "";
            }
            rawTasks.add(new RawTask(
                rawId, type, skillName, instruction, input,
                expectedOutput, acceptanceCriteria, dependencies));
        }
        if (rawTasks.isEmpty() || rawTasks.size() > maxTasks) {
            return rawTasks.size() > maxTasks
                ? TaskPlan.limitExceeded(rawTasks.size(), maxTasks)
                : TaskPlan.accepted(List.of(AgentTask.chat(originalText)), maxTasks);
        }

        Map<String, String> canonicalIds = new LinkedHashMap<>();
        for (int index = 0; index < rawTasks.size(); index++) {
            canonicalIds.putIfAbsent(
                rawTasks.get(index).rawId(), "task-" + (index + 1));
        }
        List<AgentTask> tasks = new ArrayList<>();
        for (int index = 0; index < rawTasks.size(); index++) {
            RawTask raw = rawTasks.get(index);
            String id = "task-" + (index + 1);
            List<String> dependencies = raw.dependencies().stream()
                .map(canonicalIds::get)
                .filter(java.util.Objects::nonNull)
                .filter(dependency -> !dependency.equals(id))
                .distinct().toList();
            tasks.add(new AgentTask(
                id, index, raw.type(), raw.skillName(),
                raw.instruction(), canonicalizeReferences(raw.input(), canonicalIds),
                raw.expectedOutput(),
                raw.acceptanceCriteria(), dependencies));
        }
        return TaskPlan.accepted(List.copyOf(tasks), maxTasks);
    }

    private AgentTaskType parseType(String value) {
        String normalized = value == null
            ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IMAGE", "IMAGE_GEN", "IMAGE_GENERATION" ->
                AgentTaskType.IMAGE_GENERATION;
            case "VISION", "IMAGE_ANALYSIS", "IMAGE_UNDERSTANDING" ->
                AgentTaskType.IMAGE_UNDERSTANDING;
            case "DOCUMENT", "DOCUMENT_SUMMARY", "DOCUMENT_ANALYSIS" ->
                AgentTaskType.DOCUMENT_ANALYSIS;
            case "SKILL" -> AgentTaskType.SKILL;
            default -> AgentTaskType.CHAT_TOOL;
        };
    }

    private List<AcceptanceCriterion> parseAcceptanceCriteria(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || criteria.size() >= 16) break;
            String path = item.path("path").asText("").trim();
            String operatorText = item.path("operator").asText("").trim();
            if (path.isBlank() || operatorText.isBlank()) continue;
            try {
                AcceptanceOperator operator = AcceptanceOperator.valueOf(
                    operatorText.toUpperCase(Locale.ROOT));
                criteria.add(new AcceptanceCriterion(
                    item.path("description").asText(""),
                    path,
                    operator,
                    item.has("expected") ? item.get("expected") : null,
                    item.path("required").asBoolean(true)));
            } catch (IllegalArgumentException ignored) {
                // 丢弃模型生成的非法条件，不能让单个坏条件破坏整个任务计划。
            }
        }
        return List.copyOf(criteria);
    }

    private JsonNode canonicalizeReferences(
        JsonNode node, Map<String, String> canonicalIds
    ) {
        if (node == null) return mapper.nullNode();
        if (node.isObject() && node.size() == 1 && node.path("$ref").isTextual()) {
            try {
                ResultReference reference = ResultReference.parse(
                    node.path("$ref").asText(), 300);
                String canonicalId = canonicalIds.get(reference.taskId());
                if (canonicalId == null) return node.deepCopy();
                ObjectNode result = mapper.createObjectNode();
                result.put("$ref", canonicalId + ".output"
                    + reference.path().substring(1));
                return result;
            } catch (ReferenceResolutionException ignored) {
                return node.deepCopy();
            }
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.fields().forEachRemaining(field -> result.set(
                field.getKey(), canonicalizeReferences(field.getValue(), canonicalIds)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonicalizeReferences(value, canonicalIds)));
            return result;
        }
        return node.deepCopy();
    }

    private String extractJson(String content) {
        if (content == null) return "{}";
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start < 0 || end < start ? "{}" : content.substring(start, end + 1);
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
        String skillName,
        String instruction,
        JsonNode input,
        JsonNode expectedOutput,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<String> dependencies
    ) {
    }
}
