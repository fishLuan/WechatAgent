package com.clawbot.wechatbot.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Sends asynchronous Markdown messages through a DingTalk custom robot. */
public final class DingTalkNotificationService implements NotificationService {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1200;

    private final String webhook;
    private final String secret;
    private final Duration timeout;
    private final long deduplicateMillis;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Map<String, Long> recentErrors = new ConcurrentHashMap<>();

    public DingTalkNotificationService(String webhook, String secret, int timeoutSeconds,
                                       int deduplicateSeconds, ObjectMapper mapper) {
        this.webhook = webhook.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.deduplicateMillis = Math.max(0, deduplicateSeconds) * 1000L;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dingtalk-notification");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void notifyLoginRequired(String loginContent) {
        StringBuilder markdown = new StringBuilder()
            .append("## 微信机器人等待登录\n\n")
            .append("- 时间：").append(now()).append("\n");
        if (isHttpUrl(loginContent)) {
            markdown.append("- 状态：登录二维码已生成\n\n")
                .append("[点击打开微信登录页面](")
                .append(escapeMarkdownUrl(loginContent.trim()))
                .append(")");
        } else {
            markdown.append("- 状态：登录二维码已在运行程序的电脑上显示\n")
                .append("- 提示：本次 SDK 未返回可供手机访问的 HTTP(S) 登录地址");
        }
        submit("微信机器人等待登录", markdown.toString());
    }

    @Override
    public void notifyLoginSuccess(String botId, String userId) {
        String markdown = "## ✅ 微信机器人登录成功\n\n"
            + "- 时间：" + now() + "\n"
            + "- Bot ID：" + maskIdentifier(botId) + "\n"
            + "- User ID：" + maskIdentifier(userId) + "\n"
            + "- 状态：机器人已开始接收微信消息";
        submit("微信机器人登录成功", markdown);
    }

    @Override
    public void notifyError(String source, Throwable error) {
        String safeSource = sanitize(source == null ? "unknown" : source);
        String errorType = error == null ? "UnknownError" : error.getClass().getSimpleName();
        String rawMessage = error == null ? "没有提供异常信息" : error.getMessage();
        String safeMessage = sanitize(rawMessage == null ? errorType : rawMessage);
        String fingerprint = safeSource + "|" + errorType + "|" + safeMessage;
        if (isDuplicate(fingerprint)) return;

        String markdown = "## ❌ ClawBot 程序异常\n\n"
            + "- 时间：" + now() + "\n"
            + "- 模块：" + escapeMarkdown(safeSource) + "\n"
            + "- 类型：" + escapeMarkdown(errorType) + "\n"
            + "- 信息：" + escapeMarkdown(safeMessage) + "\n\n"
            + "请检查运行程序的本地日志获取完整堆栈。";
        submit("ClawBot 程序异常", markdown);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private void submit(String title, String markdown) {
        executor.submit(() -> {
            try {
                send(title, markdown);
            } catch (Exception e) {
                // Never feed DingTalk failures back into the notification service.
                System.err.println("[WARN] 钉钉通知发送失败: " + e.getMessage());
            }
        });
    }

    private void send(String title, String markdown) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("msgtype", "markdown");
        ObjectNode body = root.putObject("markdown");
        body.put("title", title);
        body.put("text", markdown);

        HttpRequest request = HttpRequest.newBuilder(URI.create(signedWebhook()))
            .timeout(timeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
            .build();
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode responseJson = mapper.readTree(response.body());
        if (responseJson.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException(responseJson.path("errmsg").asText("钉钉返回未知错误"));
        }
    }

    private String signedWebhook() throws Exception {
        if (secret.isBlank()) return webhook;
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(
            mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        String separator = webhook.contains("?") ? "&" : "?";
        return webhook + separator + "timestamp=" + timestamp + "&sign="
            + URLEncoder.encode(signature, StandardCharsets.UTF_8);
    }

    private boolean isDuplicate(String fingerprint) {
        if (deduplicateMillis == 0) return false;
        long currentTime = System.currentTimeMillis();
        Long previous = recentErrors.put(fingerprint, currentTime);
        if (recentErrors.size() > 1000) {
            recentErrors.entrySet().removeIf(
                entry -> currentTime - entry.getValue() > deduplicateMillis);
        }
        return previous != null && currentTime - previous < deduplicateMillis;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String sanitized = value
            .replaceAll("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)", "$1***")
            .replaceAll("(?i)((?:api[-_ ]?key|secret|access_token|webhook)\\s*[:=]\\s*)([^\\s,;]+)", "$1***")
            .replaceAll("https://oapi\\.dingtalk\\.com/robot/send\\?[^\\s]+", "[DINGTALK_WEBHOOK]");
        if (sanitized.length() > MAX_ERROR_MESSAGE_LENGTH) {
            return sanitized.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "...";
        }
        return sanitized;
    }

    private static String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("`", "\\`")
            .replace("[", "\\[")
            .replace("]", "\\]");
    }

    private static String escapeMarkdownUrl(String value) {
        return value.replace("(", "%28").replace(")", "%29");
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("https://") || normalized.startsWith("http://");
    }

    private static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) return "未提供";
        String trimmed = value.trim();
        if (trimmed.length() <= 8) return "****";
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
