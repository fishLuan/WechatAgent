package com.clawbot.wechatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** function-calling 工具的注册、schema 汇总和安全执行入口。 */
public class FunctionToolRegistry {
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    public FunctionToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public FunctionToolRegistry register(FunctionTool tool) {
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
        if (tool == null) return error("未知工具：" + name);
        try {
            System.out.println("[TOOL_CALL] name=" + name + ", arguments=" + summarize(rawArguments, 500));
            JsonNode arguments = mapper.readTree(rawArguments == null ? "{}" : rawArguments);
            String result = tool.execute(arguments);
            System.out.println("[TOOL_RESULT] name=" + name + ", result=" + summarize(result, 500));
            return result;
        } catch (Exception e) {
            System.err.println("[TOOL_ERROR] name=" + name + ", error=" + e.getMessage());
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
}
