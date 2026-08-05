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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** 只负责构造和发送兼容 OpenAI 协议的 DeepSeek Chat 请求。 */
public class DeepSeekClient {
    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final double temperature;
    private final int maxTokens;
    private final Duration requestTimeout;
    private final int transientRetries;
    private final Duration circuitBreakDuration;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong();

    private static final Set<Integer> TRANSIENT_STATUS_CODES =
        Set.of(429, 502, 503, 504);

    public DeepSeekClient(String apiKey, String model, String apiUrl, double temperature,
                          int maxTokens, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this(apiKey, model, apiUrl, temperature, maxTokens,
            connectTimeoutSeconds, requestTimeoutSeconds, 0, 0);
    }

    public DeepSeekClient(String apiKey, String model, String apiUrl, double temperature,
                          int maxTokens, int connectTimeoutSeconds, int requestTimeoutSeconds,
                          int transientRetries, int circuitBreakSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.apiUrl = apiUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.transientRetries = Math.max(0, transientRetries);
        this.circuitBreakDuration = Duration.ofSeconds(Math.max(0, circuitBreakSeconds));
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
        return chat(messages, tools, temperature);
    }

    public JsonNode chat(ArrayNode messages, ArrayNode tools,
                         double requestTemperature) throws Exception {
        long openUntil = circuitOpenUntilMillis.get();
        long now = System.currentTimeMillis();
        if (openUntil > now) {
            long remainingSeconds = Math.max(1, (openUntil - now + 999) / 1000);
            throw new Exception("DeepSeek 服务暂时熔断，约 "
                + remainingSeconds + " 秒后恢复尝试");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("temperature", requestTemperature);
        body.put("max_tokens", maxTokens);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        for (int attempt = 0; attempt <= transientRetries; attempt++) {
            HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                circuitOpenUntilMillis.set(0);
                return mapper.readTree(response.body());
            }
            if (TRANSIENT_STATUS_CODES.contains(response.statusCode())) {
                if (attempt < transientRetries) {
                    Thread.sleep(Math.min(
                        2_000L, 300L * (1L << Math.min(attempt, 3))));
                    continue;
                }
                if (!circuitBreakDuration.isZero()) {
                    circuitOpenUntilMillis.set(
                        System.currentTimeMillis() + circuitBreakDuration.toMillis());
                }
                throw new Exception("DeepSeek 服务繁忙（HTTP " + response.statusCode()
                    + "），已重试 " + transientRetries + " 次，暂停请求 "
                    + circuitBreakDuration.toSeconds() + " 秒");
            }
            String text = response.body() == null ? "" : response.body();
            throw new Exception("DeepSeek 请求失败，HTTP " + response.statusCode() + "："
                + (text.length() > 300 ? text.substring(0, 300) + "..." : text));
        }
        throw new IllegalStateException("DeepSeek 重试流程异常结束");
    }
}
