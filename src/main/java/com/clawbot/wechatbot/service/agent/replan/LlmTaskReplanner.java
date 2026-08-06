package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.AcceptanceCriterion;
import com.clawbot.wechatbot.service.agent.AcceptanceOperator;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 使用低温度模型生成受控的局部任务图修改，不直接执行任何任务。 */
public final class LlmTaskReplanner implements TaskReplanner {
    private static final String PROMPT = """
        你是 Agent 局部重规划器，只输出严格 JSON，不回答用户问题。
        只能修复当前失败任务及其依赖关系，禁止扩大用户原始需求，禁止修改已验证任务。
        允许的 mutation type 只有：
        - RETRY_TASK：修正外部临时失败后重试，不包含 task。
        - REPLACE_TASK：替换失败任务，task.id 必须保持 target_task_id。
        - INSERT_BEFORE：在失败任务前增加一个补充步骤，新 task.id 必须唯一。
        - ABORT_BRANCH：无法安全修复时终止该任务及其下游，不包含 task。

        输出格式：
        {"reason":"重规划原因","mutations":[
          {"type":"RETRY_TASK","target_task_id":"task-1","reason":"原因"},
          {"type":"REPLACE_TASK","target_task_id":"task-2","reason":"原因","task":{
            "id":"task-2","order":1,"type":"CHAT_TOOL","skill_name":"",
            "instruction":"完整任务","input":{},"expected_output":{},
            "acceptance_criteria":[],"depends_on":[]}}
        ]}

        所有新任务必须自包含，input 不得猜测未知值；验收字段 path 必须以 $ 开头。
        不得返回其他操作，不得重复修改同一目标；无法修复时使用 ABORT_BRANCH。
        """;

    private static final String IMAGE_OUTPUT_RULE = """
        IMAGE_GENERATION 返回二进制 IMAGE 附件，不返回 image_url、url 或图片链接。
        图片任务因这些字段不存在而验收失败时，应移除错误验收字段并保留
        IMAGE_GENERATION 类型，不得因此使用 ABORT_BRANCH。
        """;

    private static final String VOICE_INPUT_RULE = """
        voice-reply 朗读前置结果时使用 input={} 和 depends_on，禁止将字符串 $ref
        直接作为整个 input。语音 Skill 会通过 dependencyText 获取前置文字。
        """;

    private final DeepSeekClient client;
    private final ObjectMapper mapper;
    private final SkillCatalog skills;

    public LlmTaskReplanner(DeepSeekClient client, SkillCatalog skills) {
        this.client = client;
        this.mapper = client.mapper();
        this.skills = skills;
    }

    @Override
    public ReplanResult replan(ReplanRequest request) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", IMAGE_OUTPUT_RULE + VOICE_INPUT_RULE));
        messages.add(message(
            "system", PROMPT + "\n可用 Skill：\n" + skills.plannerCatalog()));
        messages.add(message(
            "user", mapper.writeValueAsString(requestNode(request))));
        JsonNode response = client.chat(messages, mapper.createArrayNode(), 0.0D);
        String content = response.path("choices").path(0).path("message")
            .path("content").asText("");
        return parse(content);
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }

    ReplanResult parse(String content) throws Exception {
        JsonNode root = mapper.readTree(extractJson(content));
        List<PlanMutation> mutations = new ArrayList<>();
        JsonNode items = root.path("mutations");
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (!item.isObject()) continue;
                try {
                    PlanMutationType type = PlanMutationType.valueOf(
                        item.path("type").asText("").trim().toUpperCase(Locale.ROOT));
                    String targetId = item.path("target_task_id").asText("").trim();
                    AgentTask task = item.path("task").isObject()
                        ? parseTask(item.path("task")) : null;
                    mutations.add(new PlanMutation(
                        type, targetId, task, item.path("reason").asText("")));
                } catch (IllegalArgumentException ignored) {
                    // 非法操作由解析层丢弃，空结果会被后续校验器整体拒绝。
                }
            }
        }
        return new ReplanResult(mutations, root.path("reason").asText(""));
    }

    private AgentTask parseTask(JsonNode node) {
        String id = node.path("id").asText("").trim();
        int order = Math.max(0, node.path("order").asInt(0));
        AgentTaskType type = parseTaskType(node.path("type").asText(""));
        String skillName = node.path("skill_name").asText("").trim();
        String instruction = node.path("instruction").asText("").trim();
        JsonNode input = node.path("input").isObject()
            ? node.path("input") : mapper.createObjectNode();
        JsonNode expectedOutput = node.path("expected_output").isObject()
            ? node.path("expected_output") : mapper.createObjectNode();
        List<String> dependencies = new ArrayList<>();
        if (node.path("depends_on").isArray()) {
            node.path("depends_on").forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    dependencies.add(value.asText().trim());
                }
            });
        }
        return new AgentTask(
            id, order, type, skillName, instruction, input, expectedOutput,
            parseCriteria(node.path("acceptance_criteria")), dependencies);
    }

    private List<AcceptanceCriterion> parseCriteria(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<AcceptanceCriterion> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 16) break;
            try {
                result.add(new AcceptanceCriterion(
                    item.path("description").asText(""),
                    item.path("path").asText(""),
                    AcceptanceOperator.valueOf(
                        item.path("operator").asText("").toUpperCase(Locale.ROOT)),
                    item.has("expected") ? item.get("expected") : null,
                    item.path("required").asBoolean(true)));
            } catch (IllegalArgumentException ignored) {
                // 忽略单条非法验收规则。
            }
        }
        return List.copyOf(result);
    }

    private AgentTaskType parseTaskType(String value) {
        try {
            return AgentTaskType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return AgentTaskType.CHAT_TOOL;
        }
    }

    private ObjectNode requestNode(ReplanRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("original_user_request", request.originalUserRequest());
        root.set("failed_task", taskNode(request.failedTask()));
        root.put("failed_result", compactResult(request.failedResult()));
        ObjectNode evaluation = root.putObject("evaluation");
        evaluation.put("decision", request.evaluation().decision().name());
        evaluation.put("code", request.evaluation().code());
        evaluation.put("reason", request.evaluation().reason());
        evaluation.set("failed_criteria",
            mapper.valueToTree(request.evaluation().failedCriteria()));
        ObjectNode verified = root.putObject("verified_results");
        request.verifiedResults().forEach((id, result) ->
            verified.put(id, compactResult(result)));
        ArrayNode remaining = root.putArray("remaining_tasks");
        request.remainingTasks().forEach(task -> remaining.add(taskNode(task)));
        root.put("remaining_task_budget", request.remainingTaskBudget());
        return root;
    }

    private ObjectNode taskNode(AgentTask task) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", task.id());
        node.put("order", task.order());
        node.put("type", task.type().name());
        node.put("skill_name", task.skillName());
        node.put("instruction", task.instruction());
        node.set("input", task.input());
        node.set("expected_output", task.expectedOutput());
        node.set("acceptance_criteria", mapper.valueToTree(task.acceptanceCriteria()));
        node.set("depends_on", mapper.valueToTree(task.dependencies()));
        return node;
    }

    private String compactResult(AgentTaskResult result) {
        if (result == null) return "";
        String value = result.succeeded() ? result.text() : result.error();
        if (value == null) return "";
        return value.length() <= 3000 ? value : value.substring(0, 3000);
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
}
