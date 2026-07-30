package com.clawbot.wechatbot.feature.bilibili.source.client;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 带超时、重试和基础限流的 B 站公开页面/API 客户端。 */
@Component
public class BilibiliHttpClient {
    private static final String BILIBILI_HOME = "https://www.bilibili.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final long MIN_REQUEST_GAP_MILLIS = 350;

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxRetries;
    private boolean anonymousSessionInitialized;
    private long lastRequestAt;

    @Autowired
    public BilibiliHttpClient(BilibiliProperties properties) {
        this(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
            Duration.ofSeconds(properties.getRequestTimeoutSeconds()),
            properties.getMaxRetries()
        );
    }

    protected BilibiliHttpClient(
        HttpClient httpClient,
        Duration requestTimeout,
        int maxRetries
    ) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.maxRetries = Math.max(0, maxRetries);
    }

    public String getText(String url) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            throttle();
            try {
                HttpRequest request = browserGet(url);
                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response.body();
                if (status != 429 && status < 500) {
                    throw new IllegalStateException("B站请求失败，HTTP " + status);
                }
                lastFailure = new IllegalStateException("B站请求失败，HTTP " + status);
            } catch (Exception e) {
                lastFailure = e;
            }
            Thread.sleep(Math.min(1000L * (attempt + 1), 3000L));
        }
        throw lastFailure == null ? new IllegalStateException("B站请求失败") : lastFailure;
    }

    public String getAnonymousSearchText(String url) throws Exception {
        initializeAnonymousSession();
        try {
            String body = getText(url);
            return isPermissionLimitedBody(body) ? null : body;
        } catch (Exception e) {
            if (isPermissionLimitedError(e)) return null;
            throw e;
        }
    }

    public String resolveFinalUrl(String url) throws Exception {
        throttle();
        HttpRequest request = browserGet(url);
        HttpResponse<String> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("B站短链解析失败，HTTP " + status);
        }
        return response.uri().toString();
    }

    private HttpRequest browserGet(String url) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .GET()
            .build();
    }

    private synchronized void initializeAnonymousSession() {
        if (anonymousSessionInitialized) return;
        try {
            throttle();
            HttpRequest request = browserGet(BILIBILI_HOME);
            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            anonymousSessionInitialized = status >= 200 && status < 500;
        } catch (Exception ignored) {
            anonymousSessionInitialized = true;
        }
    }

    private boolean isPermissionLimitedBody(String body) {
        if (body == null || body.isBlank()) return true;
        return body.contains("\"code\":-412")
            || body.contains("\"code\":-352")
            || body.contains("\"code\":-403");
    }

    private boolean isPermissionLimitedError(Exception e) {
        String message = e.getMessage();
        if (message == null) return false;
        return message.contains("HTTP 403")
            || message.contains("HTTP 412")
            || message.contains("HTTP 429");
    }

    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = MIN_REQUEST_GAP_MILLIS - (now - lastRequestAt);
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }
}
