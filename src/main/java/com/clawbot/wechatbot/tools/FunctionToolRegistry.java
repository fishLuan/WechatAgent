package com.clawbot.wechatbot.tools;

import com.clawbot.wechatbot.confirmation.ConfirmationService;
import com.clawbot.wechatbot.confirmation.PendingConfirmation;
import com.clawbot.wechatbot.confirmation.RiskDecision;
import com.clawbot.wechatbot.confirmation.RiskPolicy;
import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** function-calling 工具的注册、schema 汇总和安全执行入口。 */
public class FunctionToolRegistry {
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final ConfirmationService confirmations;
    private final RiskPolicy riskPolicy;
    private final AgentRequestContextHolder requestContextHolder;

    public FunctionToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
        this.confirmations = null;
        this.riskPolicy = null;
        this.requestContextHolder = null;
    }

    public FunctionToolRegistry(ObjectMapper mapper, List<FunctionTool> tools) {
        this(mapper);
        tools.forEach(this::register);
    }

    public FunctionToolRegistry(ObjectMapper mapper, List<FunctionTool> tools,
                                ConfirmationService confirmations, RiskPolicy riskPolicy,
                                AgentRequestContextHolder requestContextHolder) {
        this.mapper = mapper;
        this.confirmations = confirmations;
        this.riskPolicy = riskPolicy;
        this.requestContextHolder = requestContextHolder;
        tools.forEach(this::register);
    }

    public FunctionToolRegistry register(FunctionTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public String registeredNames() {
        return String.join(", ", tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    public ArrayNode definitions() {
        return definitionsExcluding(Set.of());
    }

    public ArrayNode definitionsExcluding(Set<String> excludedNames) {
        ArrayNode result = mapper.createArrayNode();
        tools.values().stream()
            .filter(tool -> excludedNames == null || !excludedNames.contains(tool.name()))
            .forEach(tool -> result.add(tool.definition()));
        return result;
    }

    public String execute(String name, String rawArguments) {
        return executeWithOutcome(name, rawArguments).content();
    }

    public ToolExecutionOutcome executeWithOutcome(String name, String rawArguments) {
        FunctionTool tool = tools.get(name);
        if (tool == null) {
            return failure(
                "UNKNOWN_TOOL", "未知工具：" + name, false);
        }
        try {
            JsonNode arguments = mapper.readTree(rawArguments == null ? "{}" : rawArguments);
            ToolExecutionOutcome confirmation = requireConfirmation(name, arguments);
            if (confirmation != null) return confirmation;
            String content = tool.execute(arguments);
            boolean success = isSuccessful(content);
            return new ToolExecutionOutcome(
                content,
                success,
                !success,
                success ? "" : "TOOL_REPORTED_FAILURE");
        } catch (Exception e) {
            return failure(
                "TOOL_EXECUTION_FAILED", "工具执行失败：" + e.getMessage(), true);
        }
    }

    private ToolExecutionOutcome requireConfirmation(String name, JsonNode arguments) throws Exception {
        if (confirmations == null || riskPolicy == null || requestContextHolder == null
            || confirmations.isAuthorized()) return null;
        AgentRequestContext context = requestContextHolder.current();
        if (!context.hasUser()) return null;
        RiskDecision risk = riskPolicy.evaluate(name, arguments);
        if (!risk.confirmationRequired()) return null;
        PendingConfirmation pending = confirmations.create(context, name, arguments, risk);
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("confirmation_required", true);
        result.put("confirmation_id", pending.getId());
        result.put("execution_status", "WAITING_CONFIRMATION");
        result.put("message", "是否确认" + risk.summary() + "？\n\n"
            + "回复“确认”继续执行；\n"
            + "回复“不执行”保留现有设置。\n\n"
            + "在你确认前，系统不会执行该操作。\n"
            + "任务编号：" + pending.getId() + "（仅在同时有多个待确认任务时使用）");
        return new ToolExecutionOutcome(result.toString(), true, false, "CONFIRMATION_REQUIRED");
    }

    private boolean isSuccessful(String content) {
        if (content == null || content.isBlank()) return false;
        try {
            JsonNode result = mapper.readTree(content);
            if (result.has("success")) return result.path("success").asBoolean(false);
            if (result.has("error_code")) return result.path("error_code").asInt(-1) == 0;
            if (result.has("error") && !result.path("error").asText("").isBlank()) return false;
        } catch (Exception ignored) {
            // 普通文本工具结果只要非空就视为成功。
        }
        return true;
    }

    private ToolExecutionOutcome failure(
        String code, String message, boolean retryable
    ) {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("code", code);
        result.put("retryable", retryable);
        result.put("error", message);
        try {
            return new ToolExecutionOutcome(
                mapper.writeValueAsString(result), false, retryable, code);
        } catch (Exception ignored) {
            return new ToolExecutionOutcome(
                "{\"success\":false}", false, retryable, code);
        }
    }
}
