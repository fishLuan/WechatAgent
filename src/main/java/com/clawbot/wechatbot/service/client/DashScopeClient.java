package com.clawbot.wechatbot.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 只负责 DashScope 的鉴权、JSON 请求和结果文件下载。 */
public class DashScopeClient {
    private final String apiKey;
    private final String endpoint;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public DashScopeClient(String apiKey, String endpoint, int connectTimeoutSeconds,
                           int requestTimeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
        this.mapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode post(JsonNode body, String operation) throws Exception {
        ensureConfigured();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new Exception(operation + "失败，HTTP " + response.statusCode() + "：" + preview(response.body()));
        }
        JsonNode result = mapper.readTree(response.body());
        String error = result.path("message").asText("");
        if (!error.isBlank()) throw new Exception(operation + "失败：" + error);
        return result;
    }

    public byte[] download(String url, String operation) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(requestTimeout).header("User-Agent", "Mozilla/5.0").GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            throw new Exception(operation + "下载失败，HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void ensureConfigured() {
        if (!isConfigured()) throw new IllegalStateException("DASHSCOPE_API_KEY 未配置");
    }

    private static String preview(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
