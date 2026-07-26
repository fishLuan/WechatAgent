package com.clawbot.wechatbot.tools.webPageTool;

import com.clawbot.wechatbot.tools.webaccess.SafeHttpFetcher;
import com.clawbot.wechatbot.tools.webaccess.UrlAccessPolicy;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 独立网页正文抓取客户端，不依赖微信机器人和大模型。 */
public class WebPageExtractClient {
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final Pattern TITLE =
        Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern DESCRIPTION = Pattern.compile(
        "(?is)<meta\\s+[^>]*(?:name|property)=[\"']"
            + "(?:description|og:description)[\"'][^>]*content=[\"'](.*?)[\"'][^>]*>");
    private static final Pattern CONTENT_TYPE_CHARSET =
        Pattern.compile("(?i)charset=([^;]+)");

    private final SafeHttpFetcher fetcher;
    private final int defaultMaxBodyChars;

    public WebPageExtractClient(
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        int defaultMaxBodyChars
    ) {
        this(
            createDefaultFetcher(
                connectTimeoutSeconds,
                requestTimeoutSeconds,
                DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_MAX_REDIRECTS),
            defaultMaxBodyChars
        );
    }

    public WebPageExtractClient(
        HttpClient http, Duration requestTimeout, int defaultMaxBodyChars
    ) {
        this(
            new SafeHttpFetcher(
                http,
                new UrlAccessPolicy(Set.of(80, 443)),
                requestTimeout,
                DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_MAX_REDIRECTS,
                "ClawBot-WebPageExtractTool/1.0"),
            defaultMaxBodyChars
        );
    }

    public WebPageExtractClient(SafeHttpFetcher fetcher, int defaultMaxBodyChars) {
        if (fetcher == null) throw new IllegalArgumentException("fetcher must not be null");
        if (defaultMaxBodyChars < 1) {
            throw new IllegalArgumentException("defaultMaxBodyChars must be greater than 0");
        }
        this.fetcher = fetcher;
        this.defaultMaxBodyChars = defaultMaxBodyChars;
    }

    public WebPageExtractResult extract(WebPageExtractRequest request) {
        if (request == null || request.getUrl().isEmpty()) {
            return WebPageExtractResult.error("", "url 参数不能为空");
        }

        URI uri;
        try {
            uri = normalizeUri(request.getUrl());
        } catch (Exception e) {
            return WebPageExtractResult.error(request.getUrl(), "URL 格式无效");
        }

        try {
            SafeHttpFetcher.TextFetchResponse response = fetcher.fetchText(uri);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return WebPageExtractResult.error(
                    request.getUrl(), "网页请求失败，HTTP " + status);
            }

            String html = decode(response.body(), response.contentType());
            String title = cleanText(firstMatch(TITLE, html));
            String description = cleanText(firstMatch(DESCRIPTION, html));
            String body = extractBodyText(html);
            int requestedMax = request.getMaxBodyChars();
            int maxChars = requestedMax > 0
                ? Math.min(requestedMax, defaultMaxBodyChars)
                : defaultMaxBodyChars;
            if (body.length() > maxChars) {
                body = body.substring(0, maxChars).trim();
            }

            return WebPageExtractResult.ok(
                request.getUrl(),
                response.finalUri().toString(),
                status,
                title,
                description,
                body
            );
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) message = "未知错误";
            return WebPageExtractResult.error(request.getUrl(), "网页访问被拒绝：" + message);
        }
    }

    private static SafeHttpFetcher createDefaultFetcher(
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        int maxResponseBytes,
        int maxRedirects
    ) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return new SafeHttpFetcher(
            http,
            new UrlAccessPolicy(Set.of(80, 443)),
            Duration.ofSeconds(requestTimeoutSeconds),
            maxResponseBytes,
            maxRedirects,
            "ClawBot-WebPageExtractTool/1.0"
        );
    }

    private URI normalizeUri(String url) {
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "http://", 0, 7)
                && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            trimmed = "https://" + trimmed;
        }
        return URI.create(trimmed);
    }

    private String decode(byte[] bytes, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        Matcher matcher =
            CONTENT_TYPE_CHARSET.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try {
                charset = Charset.forName(
                    matcher.group(1).trim().replace("\"", ""));
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
        text = text.replaceAll(
            "(?is)</(p|div|section|article|header|footer|li|h[1-6]|br|tr)>", "\n");
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
