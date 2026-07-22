package com.clawbot.wechatbot.tools.webPageTool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 独立网页正文抓取客户端，不依赖微信机器人和大模型。 */
public class WebPageExtractClient {
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern DESCRIPTION = Pattern.compile(
        "(?is)<meta\\s+[^>]*(?:name|property)=[\"'](?:description|og:description)[\"'][^>]*content=[\"'](.*?)[\"'][^>]*>");
    private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile("(?i)charset=([^;]+)");

    private final HttpClient http;
    private final Duration requestTimeout;
    private final int defaultMaxBodyChars;

    public WebPageExtractClient(int connectTimeoutSeconds, int requestTimeoutSeconds, int defaultMaxBodyChars) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.defaultMaxBodyChars = defaultMaxBodyChars;
    }

    public WebPageExtractResult extract(WebPageExtractRequest request) throws Exception {
        if (request == null || request.getUrl().isEmpty()) {
            return WebPageExtractResult.error("", "url 参数不能为空");
        }
        URI uri = normalizeUri(request.getUrl());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return WebPageExtractResult.error(request.getUrl(), "仅支持 http/https URL");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("User-Agent", "ClawBot-WebPageExtractTool/1.0")
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .GET()
            .build();
        HttpResponse<byte[]> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return WebPageExtractResult.error(request.getUrl(), "网页请求失败，HTTP " + status);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.isEmpty()
            && !contentType.toLowerCase(Locale.ROOT).contains("text")
            && !contentType.toLowerCase(Locale.ROOT).contains("html")
            && !contentType.toLowerCase(Locale.ROOT).contains("xml")) {
            return WebPageExtractResult.error(request.getUrl(), "不支持的 Content-Type: " + contentType);
        }

        String html = decode(response.body(), contentType);
        String title = cleanText(firstMatch(TITLE, html));
        String description = cleanText(firstMatch(DESCRIPTION, html));
        String body = extractBodyText(html);
        int maxChars = request.getMaxBodyChars() > 0 ? request.getMaxBodyChars() : defaultMaxBodyChars;
        if (maxChars > 0 && body.length() > maxChars) {
            body = body.substring(0, maxChars).trim();
        }

        return WebPageExtractResult.ok(
            request.getUrl(),
            response.uri().toString(),
            status,
            title,
            description,
            body
        );
    }

    private URI normalizeUri(String url) {
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return URI.create(trimmed);
    }

    private String decode(byte[] bytes, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        Matcher matcher = CONTENT_TYPE_CHARSET.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try {
                charset = Charset.forName(matcher.group(1).trim().replace("\"", ""));
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        return new String(bytes, charset);
    }

    private String extractBodyText(String html) {
        String text = html == null ? "" : html;
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        text = text.replaceAll("(?is)<!--.*?-->", " ");
        text = text.replaceAll("(?is)</(p|div|section|article|header|footer|li|h[1-6]|br|tr)>", "\n");
        text = text.replaceAll("(?is)<[^>]+>", " ");
        return cleanText(text);
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String cleanText(String text) {
        String cleaned = decodeHtmlEntities(text == null ? "" : text);
        cleaned = cleaned.replace('\u00A0', ' ');
        cleaned = cleaned.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        cleaned = cleaned.replaceAll("\\n\\s+", "\n");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.trim();
    }

    private String decodeHtmlEntities(String text) {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'");
    }
}
