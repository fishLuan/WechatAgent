package com.clawbot.wechatbot.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 只负责构造和发送兼容 OpenAI 协议的 DeepSeek Chat 请求。 */
public class DeepSeekClient {
    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final double temperature;
    private final int maxTokens;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekClient(String apiKey, String model, String apiUrl, double temperature,
                          int maxTokens, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.apiUrl = apiUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode chat(ArrayNode messages, ArrayNode tools) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
            System.out.println("[API] 本次请求携带 " + tools.size() + " 个工具定义");
        } else {
            System.out.println("[API] 本次请求没有携带工具定义");
        }

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            String text = response.body() == null ? "" : response.body();
            throw new Exception("DeepSeek 请求失败，HTTP " + response.statusCode() + "："
                + (text.length() > 300 ? text.substring(0, 300) + "..." : text));
        }
        return mapper.readTree(response.body());
    }
}