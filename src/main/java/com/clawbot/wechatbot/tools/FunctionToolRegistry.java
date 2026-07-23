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

    public FunctionToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
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
            JsonNode arguments = mapper.readTree(rawArguments == null ? "{}" : rawArguments);
            return execute(name, arguments);
        } catch (Exception e) {
            return error("工具执行失败：" + e.getMessage());
        }
    }

    public String execute(String name, JsonNode arguments) {
        FunctionTool tool = tools.get(name);
        if (tool == null) return error("未知的工具：" + name);
        try {
            return tool.execute(arguments == null ? mapper.createObjectNode() : arguments);
        } catch (Exception e) {
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

    public static class Builder {
        private ObjectMapper mapper = new ObjectMapper();
        private final FunctionToolRegistry registry = new FunctionToolRegistry(mapper);

        public Builder mapper(ObjectMapper mapper) {
            this.mapper = mapper == null ? new ObjectMapper() : mapper;
            return this;
        }

        public Builder register(FunctionTool tool) {
            registry.register(tool);
            return this;
        }

        public FunctionToolRegistry build() {
            FunctionToolRegistry result = new FunctionToolRegistry(mapper);
            registry.tools.values().forEach(result::register);
            return result;
        }
    }
}
