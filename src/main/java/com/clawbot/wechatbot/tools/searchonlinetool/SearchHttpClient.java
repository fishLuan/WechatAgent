package com.clawbot.wechatbot.tools.searchonlinetool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

class SearchHttpClient {

    static final String BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    final HttpClient http;
    final Duration requestTimeout;

    SearchHttpClient(int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    }

    String postJson(String url, String authHeader, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json");
        if (authHeader != null && !authHeader.isBlank()) {
            b.header("Authorization", authHeader);
        }
        HttpRequest request = b
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> resp = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int s = resp.statusCode();
        return (s >= 200 && s < 300) ? resp.body() : null;
    }

    String getHtmlAsBrowser(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .GET()
            .build();

        HttpResponse<String> resp = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.statusCode() == 200 ? resp.body() : null;
    }
}