package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.util.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * DeepSeek 文本对话 —— ChatService 的实现类
 * 负责构造请求、调用 API、解析响应
 */
public class DeepSeekChatService implements ChatService {

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final String systemPrompt;
    private final HttpClient http;

    public DeepSeekChatService(BotConfig config) {
        this.apiKey = config.getDeepSeekApiKey();
        this.model = config.getDeepSeekModel();
        this.apiUrl = config.getDeepSeekUrl();
        this.systemPrompt = config.getSystemPrompt();
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    @Override
    public String chat(String userText, String history) throws Exception {
        String messages =
            "[{\"role\":\"system\",\"content\":" + JsonUtils.escape(systemPrompt) + "}"
            + (history != null && !history.isEmpty() ? "," + history : "")
            + ",{\"role\":\"user\",\"content\":" + JsonUtils.escape(userText) + "}]";

        String body = "{"
            + "\"model\":\"" + model + "\","
            + "\"messages\":" + messages + ","
            + "\"temperature\":0.8,"
            + "\"max_tokens\":1024"
            + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            String body200 = response.body();
            throw new Exception("HTTP " + response.statusCode()
                + ": " + (body200.length() > 200 ? body200.substring(0, 200) + "..." : body200));
        }

        String content = JsonUtils.extractContent(response.body());
        if (content == null || content.trim().isEmpty()) {
            throw new Exception("Could not parse response JSON");
        }
        return content.trim();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}