package com.clawbot.wechatbot.tools;

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

    public FunctionToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public FunctionToolRegistry(ObjectMapper mapper, List<FunctionTool> tools) {
        this(mapper);
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
