package com.xwc.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@PropertySource("classpath:config.properties")
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private final double temperature;
    private final int maxTokens;
    private final int maxHistoryRounds;
    private final boolean enabled;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();

    // ⭐ 核心：按用户 ID 存对话历史
    //    userId -> [ {role:"user", content:"你好"}, {role:"assistant", content:"你好呀！"}, ... ]
    public LlmService(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:}") String model,
            @Value("${llm.system-prompt:你是一个友善、幽默、智能的微信聊天助手，用中文简短回复。}") String systemPrompt,
            @Value("${llm.temperature:0.7}") double temperature,
            @Value("${llm.max-tokens:512}") int maxTokens,
            @Value("${llm.max-history-rounds:10}") int maxHistoryRounds
    ) {
        this.baseUrl = trim(baseUrl);
        this.apiKey = trim(apiKey);
        this.model = trim(model);
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxHistoryRounds = maxHistoryRounds;
        this.enabled = !this.baseUrl.isEmpty() && !this.apiKey.isEmpty() && !this.model.isEmpty();
        // 如果三个核心配置任何一个是空字符串，就认为 LLM 没配置，后面会回退到本地关键词

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();

        if (enabled) {
            log.info("LLM 已启用: baseUrl={}, model={}", this.baseUrl, this.model);
        } else {
            log.warn("LLM 未启用（缺少 base-url / api-key / model 配置），将使用本地关键词回复");
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getModel() { return model; }

    public String chat(String userId, String userText) throws IOException {
        if (!enabled) {
            throw new IllegalStateException("LLM 未配置，请在 application.properties 中设置 llm.base-url / llm.api-key / llm.model");
        }
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(userText, "userText 不能为空");

        // 1. 取出或创建该用户的对话列表
        List<ChatMessage> history = sessions.computeIfAbsent(userId, k -> new ArrayList<>());
        // 2. 把用户这句话加进历史
        history.add(new ChatMessage("user", userText));
        // 3. 调大模型 API
        String reply = callOpenAiCompletions(history);
        // 4. 把模型的回复也加进历史（下次对话时模型能看到这条
        history.add(new ChatMessage("assistant", reply));
        // 5. 如果历史太长了，删掉最老的（防止把所有历史都发过去，省钱也更快）
        while (history.size() > maxHistoryRounds * 2) {
            history.remove(0);
        }
        return reply;
    }

    public void clearSession(String userId) {
        sessions.remove(userId);
        log.info("用户 {} 的对话历史已清空", userId);
    }

    public void clearAllSessions() {
        sessions.clear();
        log.info("所有对话历史已清空");
    }

    private String callOpenAiCompletions(List<ChatMessage> history) throws IOException {
        // 构造请求消息列表：
        // [0] = system prompt (人设)   ← 每次都在最前面
        // [1..n] = 用户和机器人的对话历史
        List<ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatMessage("system", systemPrompt));
        requestMessages.addAll(history);

        // 构造 JSON 请求体，格式完全是 OpenAI 标准：
        // {
        //   "model": "deepseek-v4-flash",
        //   "temperature": 0.7,
        //   "max_tokens": 512,
        //   "messages": [
        //     {"role": "system", "content": "你是..."},
        //     {"role": "user", "content": "你好"},
        //     {"role": "assistant", "content": "你好呀！"}
        //   ]
        // }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        ArrayNode messagesNode = root.putArray("messages");
        for (ChatMessage m : requestMessages) {
            ObjectNode msg = messagesNode.addObject();
            msg.put("role", m.role());
            msg.put("content", m.content());
        }
        String jsonBody = objectMapper.writeValueAsString(root);

        // 发 HTTP POST 请求
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        long start = System.currentTimeMillis();
        // 解析响应
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("LLM 调用失败: status={}, body={}", response.code(), body);
                throw new IOException("LLM API 返回错误 (" + response.code() + "): " + body);
            }
            JsonNode json = objectMapper.readTree(body);
            // 响应格式：
            // {
            //   "choices": [
            //     { "message": {"content": "这是模型的回复内容"} }
            //   ]
            // }
            JsonNode choices = json.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                long cost = System.currentTimeMillis() - start;
                log.info("LLM 回复成功 ({}ms, {} 字)", cost, content.length());
                return content.trim();
            }
            log.warn("LLM 返回格式异常: {}", body);
            return "（我好像没理解你的意思，再说一遍？）";
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public record ChatMessage(String role, String content) {}
}