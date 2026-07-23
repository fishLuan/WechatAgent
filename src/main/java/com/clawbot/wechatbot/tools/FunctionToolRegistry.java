package com.clawbot.wechatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** function-calling 工具的注册、schema 汇总和安全执行入口。 */
public class FunctionToolRegistry {
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final FunctionToolLogger logger;

    public FunctionToolRegistry(ObjectMapper mapper) {
        this(mapper, FunctionToolLogger.CONSOLE);
    }

    public FunctionToolRegistry(ObjectMapper mapper, FunctionToolLogger logger) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.logger = logger == null ? FunctionToolLogger.NOOP : logger;
    }

    public static Builder builder() {
        return new Builder();
    }

    public FunctionToolRegistry register(FunctionTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        tools.put(tool.name(), tool);
        return this;
    }

    public ArrayNode definitions() {
        ArrayNode result = mapper.createArrayNode();
        tools.values().forEach(tool -> result.add(tool.definition()));
        return result;
    }

    public String execute(String name, String rawArguments) {
        FunctionTool tool = tools.get(name);
        if (tool == null) return error("未知的工具：" + name);
        try {
            logger.log("[TOOL_CALL] name=" + name + ", arguments=" + summarize(rawArguments, 500));
            JsonNode arguments = mapper.readTree(rawArguments == null ? "{}" : rawArguments);
            return execute(name, arguments);
        } catch (Exception e) {
            logger.log("[TOOL_ERROR] name=" + name + ", error=" + e.getMessage());
            return error("工具执行失败：" + e.getMessage());
        }
    }

    public String execute(String name, JsonNode arguments) {
        FunctionTool tool = tools.get(name);
        if (tool == null) return error("未知的工具：" + name);
        try {
            logger.log("[TOOL_CALL] name=" + name + ", arguments=" + summarize(arguments == null ? "{}" : arguments.toString(), 500));
            String result = tool.execute(arguments == null ? mapper.createObjectNode() : arguments);
            logger.log("[TOOL_RESULT] name=" + name + ", result=" + summarize(result, 500));
            return result;
        } catch (Exception e) {
            logger.log("[TOOL_ERROR] name=" + name + ", error=" + e.getMessage());
            return error("工具执行失败：" + e.getMessage());
        }
    }

    private String error(String message) {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception ignored) {
            return "{\"success\":false}";
        }
    }

    private String summarize(String text, int maxLength) {
        if (text == null) return "{}";
        String oneLine = text.replace("\r", "\\r").replace("\n", "\\n");
        return oneLine.length() <= maxLength ? oneLine : oneLine.substring(0, maxLength) + "...";
    }

    public static class Builder {
        private ObjectMapper mapper = new ObjectMapper();
        private FunctionToolLogger logger = FunctionToolLogger.NOOP;
        private final FunctionToolRegistry registry = new FunctionToolRegistry(mapper, logger);

        public Builder mapper(ObjectMapper mapper) {
            this.mapper = mapper == null ? new ObjectMapper() : mapper;
            return this;
        }

        public Builder logger(FunctionToolLogger logger) {
            this.logger = logger == null ? FunctionToolLogger.NOOP : logger;
            return this;
        }

        public Builder register(FunctionTool tool) {
            registry.register(tool);
            return this;
        }

        public FunctionToolRegistry build() {
            FunctionToolRegistry result = new FunctionToolRegistry(mapper, logger);
            registry.tools.values().forEach(result::register);
            return result;
        }
    }
}
