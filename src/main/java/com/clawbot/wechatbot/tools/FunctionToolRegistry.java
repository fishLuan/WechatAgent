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

    public String registeredNames() {
        return String.join(", ", tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    public ArrayNode definitions() {
        ArrayNode result = mapper.createArrayNode();
        tools.values().forEach(tool -> result.add(tool.definition()));
        return result;
    }

    public String execute(String name, String rawArguments) {
        System.out.println("[TOOL] 模型调用工具 → " + name
            + "，参数: " + (rawArguments == null ? "(空)" : rawArguments));
        FunctionTool tool = tools.get(name);
        if (tool == null) {
            System.out.println("[TOOL] ✗ 工具不存在: " + name);
            return error("未知工具：" + name);
        }
        try {
            JsonNode arguments = mapper.readTree(rawArguments == null ? "{}" : rawArguments);
            String result = tool.execute(arguments);
            System.out.println("[TOOL] ✓ " + name + " 执行完成，返回: "
                + (result == null ? "(空)" : (result.length() > 200 ? result.substring(0, 200) + "..." : result)));
            return result;
        } catch (Exception e) {
            System.out.println("[TOOL] ✗ " + name + " 执行失败: " + e.getMessage());
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
}