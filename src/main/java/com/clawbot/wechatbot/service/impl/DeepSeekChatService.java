package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;

/** 负责对话和 function-calling 流程，不再承担 HTTP 或具体工具执行细节。 */
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
        // 在系统 prompt 最前面注入真实当前日期/星期/时间，彻底解决模型的"日期幻觉"
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        int day = today.getDayOfMonth();
        String weekday = chineseWeekday(today.getDayOfWeek());
        String timeStr = LocalTime.now().toString().substring(0, 5);
        String dateInject = "【重要参考信息 - 请你以此为准回答日期相关问题】"
            + "当前系统时间：" + year + "年" + month + "月" + day + "日 " + weekday + " " + timeStr
            + "。请不要再使用任何虚构的日期。";
        String fullSystem = (systemPrompt == null ? "" : systemPrompt) + "\n" + dateInject;
        messages.add(message("system", fullSystem));
        appendHistory(messages, history);
        // 在用户消息前加一句强提示，鼓励模型调用搜索工具
        String hint = "[内部提示：当用户询问实时新闻、汇率、或你不确定的事实时，请调用 web_search 搜索工具。不要编造信息。]";
        messages.add(message("user", hint + "\n" + userText));
        System.out.println("[CHAT] 注入日期: " + year + "年" + month + "月" + day + "日 " + weekday);
        System.out.println("[CHAT] 用户输入: \"" + userText + "\"");
        System.out.println("[CHAT] 系统 prompt: " + (fullSystem.length() > 100 ? fullSystem.substring(0, 100) + "..." : fullSystem));

        for (int round = 0; round <= maxToolRounds; round++) {
            JsonNode response = client.chat(messages, toolRegistry.definitions());
            JsonNode assistant = response.path("choices").path(0).path("message");
            if (assistant.isMissingNode()) throw new Exception("模型响应中缺少 choices[0].message");

            JsonNode toolCalls = assistant.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = assistant.path("content").asText("").trim();
                if (content.isEmpty()) throw new Exception("模型未返回文本内容");
                System.out.println("[CHAT] 模型直接回答（未调用工具）");
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

    private static String chineseWeekday(DayOfWeek day) {
        switch (day) {
            case MONDAY: return "星期一";
            case TUESDAY: return "星期二";
            case WEDNESDAY: return "星期三";
            case THURSDAY: return "星期四";
            case FRIDAY: return "星期五";
            case SATURDAY: return "星期六";
            case SUNDAY: return "星期日";
            default: return "";
        }
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }
}