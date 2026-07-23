package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 负责对话和 function-calling 流程（通用调度器，不与任何具体工具耦合）。
 *
 * 工具的增删/配置/提示词全部在外部完成：
 *   - application.properties 的 systemPrompt
 *   - WeChatBot.initialize() 里的 .register(...)
 *
 * 本类只负责：按 OpenAI/DeepSeek 协议，正确组装 messages、循环执行 tool_calls 直到模型输出文本。
 */
public class DeepSeekChatService implements ChatService {
    private final DeepSeekClient client;
    private final FunctionToolRegistry toolRegistry;
    private final String systemPrompt;
    private final int maxToolRounds;
    private final ObjectMapper mapper;

    public DeepSeekChatService(DeepSeekClient client, FunctionToolRegistry toolRegistry,
                               String systemPrompt, int maxToolRounds) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.systemPrompt = systemPrompt;
        this.maxToolRounds = maxToolRounds;
        this.mapper = client.mapper();
    }

    @Override
    public String chat(String userText, String history) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", systemPrompt == null ? "" : systemPrompt));
        appendHistory(messages, history);
        messages.add(message("user", userText));

        for (int round = 0; round <= maxToolRounds; round++) {
            JsonNode response = client.chat(messages, toolRegistry.definitions());
            JsonNode assistant = response.path("choices").path(0).path("message");
            if (assistant.isMissingNode()) throw new Exception("模型响应中缺少 choices[0].message");

            JsonNode toolCalls = assistant.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = assistant.path("content").asText("").trim();
                if (content.isEmpty()) throw new Exception("模型未返回文本内容");
                return content;
            }
            if (round == maxToolRounds) throw new Exception("工具调用次数超过限制");

            messages.add(assistant.deepCopy());
            for (JsonNode call : toolCalls) {
                String callId = call.path("id").asText();
                String toolName = call.path("function").path("name").asText();
                String arguments = call.path("function").path("arguments").asText("{}");
                ObjectNode toolMessage = message("tool", toolRegistry.execute(toolName, arguments));
                toolMessage.put("tool_call_id", callId);
                toolMessage.put("name", toolName);
                messages.add(toolMessage);
            }
        }
        throw new Exception("工具调用流程异常结束");
    }

    private void appendHistory(ArrayNode messages, String history) throws Exception {
        if (history == null || history.isBlank()) return;
        JsonNode parsed = mapper.readTree("[" + history + "]");
        if (parsed.isArray()) parsed.forEach(messages::add);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }
}