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
    private static final String DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final String configuredCookie;
    private final String configuredUserAgent;
    private final Duration searchCircuitBreakerDuration;
    private final long minRequestGapMillis;
    private boolean anonymousSessionInitialized;
    private long lastRequestAt;
    private volatile long searchCircuitOpenUntil;

    @Autowired
    public BilibiliHttpClient(BilibiliProperties properties) {
        this(
            buildHttpClient(properties),
            Duration.ofSeconds(properties.getRequestTimeoutSeconds()),
            properties.getMaxRetries(),
            properties.getCookie(),
            properties.getUserAgent(),
            Duration.ofMinutes(properties.getSearchCircuitBreakerMinutes()),
            properties.getMinRequestGapMillis()
        );
    }

    protected BilibiliHttpClient(
        HttpClient httpClient,
        Duration requestTimeout,
        int maxRetries
    ) {
        this(httpClient, requestTimeout, maxRetries, "", DEFAULT_USER_AGENT,
            Duration.ofMinutes(30), 350);
    }

    protected BilibiliHttpClient(
        HttpClient httpClient,
        Duration requestTimeout,
        int maxRetries,
        String configuredCookie,
        Duration searchCircuitBreakerDuration
    ) {
        this(httpClient, requestTimeout, maxRetries, configuredCookie,
            DEFAULT_USER_AGENT,
            searchCircuitBreakerDuration, 350);
    }

    protected BilibiliHttpClient(
        HttpClient httpClient,
        Duration requestTimeout,
        int maxRetries,
        String configuredCookie,
        String configuredUserAgent,
        Duration searchCircuitBreakerDuration,
        long minRequestGapMillis
    ) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.maxRetries = Math.max(0, maxRetries);
        this.configuredCookie = sanitizeCookie(configuredCookie);
        this.configuredUserAgent = sanitizeHeader(
            configuredUserAgent, "BILIBILI_USER_AGENT");
        this.searchCircuitBreakerDuration = searchCircuitBreakerDuration;
        this.minRequestGapMillis = Math.max(100, minRequestGapMillis);
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
                if (status >= 200 && status < 300) {
                    if (isPermissionLimitedBody(response.body())) {
                        throw new BilibiliAccessLimitedException(
                            "B站接口触发访问限制（412/风控）");
                    }
                    return response.body();
                }
                if (status == 403 || status == 412 || status == 429) {
                    throw new BilibiliAccessLimitedException(
                        "B站接口触发访问限制，HTTP " + status);
                }
                if (status != 429 && status < 500) {
                    throw new IllegalStateException("B站请求失败，HTTP " + status);
                }
                lastFailure = new IllegalStateException("B站请求失败，HTTP " + status);
            } catch (Exception e) {
                if (e instanceof BilibiliAccessLimitedException limited) {
                    throw limited;
                }
                lastFailure = e;
            }
            Thread.sleep(Math.min(1000L * (attempt + 1), 3000L));
        }
        throw lastFailure == null ? new IllegalStateException("B站请求失败") : lastFailure;
    }

    public String getAnonymousSearchText(String url) throws Exception {
        rejectWhenSearchCircuitIsOpen();
        initializeAnonymousSession();
        try {
            return getText(url);
        } catch (Exception e) {
            if (e instanceof BilibiliAccessLimitedException
                || isPermissionLimitedError(e)) {
                openSearchCircuit();
                throw new BilibiliAccessLimitedException(
                    "B站实时搜索暂时受限，已暂停请求以避免继续触发风控", e);
            }
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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", configuredUserAgent)
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", "https://www.bilibili.com")
            .header("Origin", "https://www.bilibili.com");
        if (!configuredCookie.isBlank()) {
            builder.header("Cookie", configuredCookie);
        }
        return builder.GET().build();
    }

    private synchronized void initializeAnonymousSession() {
        if (anonymousSessionInitialized) return;
        if (!configuredCookie.isBlank()) {
            anonymousSessionInitialized = true;
            return;
        }
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
            || body.contains("\"code\":-403")
            || body.contains("\"errcode\":-14")
            || body.toLowerCase().contains("session timeout")
            || body.contains("错误号: 412")
            || body.contains("错误：412")
            || body.contains("security control policy")
            || body.matches("(?is).*<title>\s*出错啦!\s*-\s*aba\\.bilibili\\.com\s*</title>.*");
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
        long wait = minRequestGapMillis - (now - lastRequestAt);
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    private void rejectWhenSearchCircuitIsOpen() {
        long remaining = searchCircuitOpenUntil - System.currentTimeMillis();
        if (remaining <= 0) return;
        long minutes = Math.max(1, (remaining + 59_999L) / 60_000L);
        throw new BilibiliAccessLimitedException(
            "B站实时搜索处于风控熔断期，约 " + minutes + " 分钟后重试");
    }

    private void openSearchCircuit() {
        searchCircuitOpenUntil = System.currentTimeMillis()
            + searchCircuitBreakerDuration.toMillis();
    }

    private static String sanitizeCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) return "";
        String value = cookie.trim();
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("BILIBILI_COOKIE 不能包含换行符");
        }
        return value;
    }

    private static String sanitizeHeader(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        String sanitized = value.trim();
        if (sanitized.indexOf('\r') >= 0 || sanitized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " 不能包含换行符");
        }
        return sanitized;
    }

    private static HttpClient buildHttpClient(BilibiliProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NORMAL);
        if (properties.getCookie() == null || properties.getCookie().isBlank()) {
            builder.cookieHandler(
                new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER));
        }
        return builder.build();
    }
}
