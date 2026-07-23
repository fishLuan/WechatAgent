package com.clawbot.wechatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索工具。
 *
 * 双保险机制：
 *   1. 优先调用 博查AI Web Search API（需要 API Key，返回结构化 JSON）
 *   2. API 失败时（没额度 / 网络问题），自动降级到 必应国内版 HTML 搜索
 *      （免 Key、国内直连、通过正则解析搜索结果）
 *
 * 工具设计：
 *   - name: web_search
 *   - 参数: query (必填) —— 搜索关键词；count (可选，默认 5) —— 返回结果条数
 *   - 返回: 简洁的 JSON，包含 title/snippet/url，交给模型自行组织回复
 */
public class WebSearchTool implements FunctionTool {

    private static final String DEFAULT_ENDPOINT = "https://api.bochaai.com/v1/web-search";
    private static final String BING_URL = "https://cn.bing.com/search";
    // 模拟真实浏览器，避免 Bing 拒绝请求
    private static final String BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int DEFAULT_COUNT = 5;

    private static final Pattern BING_ALGO_BLOCK = Pattern.compile(
        "<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>",
        Pattern.DOTALL);
    private static final Pattern BING_TITLE = Pattern.compile(
        "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>\\s*</h2>",
        Pattern.DOTALL);
    private static final Pattern BING_SNIPPET = Pattern.compile(
        "<(?:p|span|div)[^>]*class=\"[^\"]*(?:b_snippet|snippet|b_caption)[^\"]*\"[^>]*>(.*?)</(?:p|span|div)>",
        Pattern.DOTALL);

    private final String apiKey;
    private final String endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public WebSearchTool(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, 30, 60);
    }

    public WebSearchTool(String apiKey, String endpoint, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();
        this.mapper = new ObjectMapper();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "联网搜索工具，用于查询实时新闻、当前日期、汇率、你不知道的事实和实时数据。"
            + "当你无法准确回答的实时或事实性问题时，请务必使用本工具。"
            + "注意：天气请使用 get_weather 专用工具。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query")
            .put("type", "string")
            .put("description", "搜索关键词，简洁准确的中文关键词");
        properties.putObject("count")
            .put("type", "integer")
            .put("description", "返回的搜索结果条数，默认 5，一般 3-8 条足够");

        ArrayNode required = parameters.putArray("required");
        required.add("query");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        System.out.println("[SEARCH] 开始联网搜索...");
        String query = arguments == null ? "" : arguments.path("query").asText("").trim();
        int count = arguments == null ? DEFAULT_COUNT
            : arguments.path("count").asInt(DEFAULT_COUNT);
        if (count < 1) count = 1;
        if (count > 20) count = 20;

        if (query.isEmpty()) {
            return error("搜索关键词 query 不能为空");
        }
        System.out.println("[SEARCH] 关键词: \"" + query + "\"，条数: " + count);

        // ============ 策略 1：博查AI API（需要 API Key） ============
        if (!apiKey.isEmpty()) {
            System.out.println("[SEARCH] 尝试博查AI API...");
            try {
                String result = searchByBochaApi(query, count);
                if (result != null) return result;
            } catch (Exception e) {
                System.out.println("[SEARCH] 博查AI异常: " + e.getMessage() + "，尝试降级到必应搜索");
            }
        } else {
            System.out.println("[SEARCH] 未配置 BOCHA_API_KEY，跳过博查AI");
        }

        // ============ 策略 2：降级到必应国内版 HTML 搜索 ============
        System.out.println("[SEARCH] 降级到必应国内版 HTML 搜索");
        return searchByBing(query, count);
    }

    // ==================== 博查AI API ====================

    private String searchByBochaApi(String query, int count) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        body.put("count", count);
        body.put("summary", true);
        body.put("freshness", "noLimit");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[SEARCH] 博查AI: HTTP " + response.statusCode() + "，耗时 " + elapsed + "ms");

        if (response.statusCode() == 403) {
            System.out.println("[SEARCH] 博查AI 无额度（403），降级到必应搜索");
            return null; // 触发降级
        }
        if (response.statusCode() != 200) {
            System.out.println("[SEARCH] 博查AI 失败（HTTP " + response.statusCode() + "），降级到必应搜索");
            return null; // 触发降级
        }

        JsonNode root = mapper.readTree(response.body());
        // 调试：打印博查AI返回的JSON结构，看看字段名对不对
        String rawJson = mapper.writeValueAsString(root);
        if (rawJson.length() > 500) {
            System.out.println("[SEARCH] 博查AI JSON 前500字符: " + rawJson.substring(0, 500));
        } else {
            System.out.println("[SEARCH] 博查AI JSON: " + rawJson);
        }

        JsonNode webPages = root.path("webPages").path("value");
        // 兜底：有些API可能用 webPages / data / list / value 等不同字段名
        if (!webPages.isArray() || webPages.size() == 0) {
            for (String[] path : new String[][] {
                {"webPages", "value"},
                {"data", "webPages", "value"},
                {"data", "value"},
                {"webPages"},
                {"results"},
                {"items"}
            }) {
                JsonNode tmp = root;
                for (String p : path) tmp = tmp.path(p);
                if (tmp.isArray() && tmp.size() > 0) {
                    webPages = tmp;
                    System.out.println("[SEARCH] 博查AI 在路径 " + String.join(".", path) + " 找到结果数组");
                    break;
                }
            }
        }

        List<SearchResult> items = new ArrayList<>();
        if (webPages.isArray()) {
            for (JsonNode item : webPages) {
                if (items.size() >= count) break;
                // 同时尝试多个可能的字段名：name/title 和 snippet/description/summary 和 url/link
                String title = firstNonEmpty(item, "name", "title");
                String snippet = firstNonEmpty(item, "snippet", "description", "summary", "text");
                String link = firstNonEmpty(item, "url", "link", "href");
                if (title.isEmpty() && snippet.isEmpty()) continue;
                items.add(new SearchResult(title, snippet, link));
            }
        }
        if (items.isEmpty()) {
            System.out.println("[SEARCH] 博查AI 解析到 0 条有效结果，降级到必应搜索");
            return null;
        }

        System.out.println("[SEARCH] ✓ 博查AI 成功，返回 " + items.size() + " 条结果");
        return buildSuccessResponse(query, items);
    }

    // ==================== 必应国内版 HTML 搜索 ====================

    private String searchByBing(String query, int count) throws Exception {
        String url = BING_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .GET()
            .build();

        System.out.println("[SEARCH] 请求必应: " + url.substring(0, Math.min(url.length(), 80)) + "...");
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[SEARCH] 必应响应: HTTP " + response.statusCode() + "，耗时 " + elapsed + "ms，HTML "
            + response.body().length() + " 字符");

        if (response.statusCode() != 200) {
            return error("必应搜索返回 HTTP " + response.statusCode());
        }

        List<SearchResult> items = parseBingHtml(response.body(), count);
        if (items.isEmpty()) {
            return error("必应搜索未能解析到有效结果，请尝试其他关键词");
        }

        System.out.println("[SEARCH] ✓ 必应搜索成功，解析到 " + items.size() + " 条结果");
        return buildSuccessResponse(query, items);
    }

    // ============ Bing HTML 解析：定位 <li class="b_algo"> 块，提取标题/链接/摘要 ============

    private static List<SearchResult> parseBingHtml(String html, int count) {
        List<SearchResult> results = new ArrayList<>();

        // 1. 切出所有 <li class="b_algo"> 块（必应每条搜索结果都包在这个标签里）
        Matcher blockMatcher = BING_ALGO_BLOCK.matcher(html);
        while (blockMatcher.find() && results.size() < count) {
            String block = blockMatcher.group(1);

            // 2. 在块内找 <h2><a href="URL">title</a></h2>
            String title = "";
            String link = "";
            Matcher titleMatcher = BING_TITLE.matcher(block);
            if (titleMatcher.find()) {
                link = titleMatcher.group(1).trim();
                title = stripHtmlTags(titleMatcher.group(2)).trim();
            }

            // 3. 在块内找摘要（class 包含 b_snippet / snippet / b_caption 之一）
            String snippet = "";
            Matcher snippetMatcher = BING_SNIPPET.matcher(block);
            if (snippetMatcher.find()) {
                snippet = stripHtmlTags(snippetMatcher.group(1)).trim();
            }

            if (!title.isEmpty() || !snippet.isEmpty()) {
                results.add(new SearchResult(title, snippet, link));
            }
        }
        return results;
    }

    private static String stripHtmlTags(String s) {
        if (s == null) return "";
        // 去掉所有 HTML 标签，解码常见的 HTML 实体
        String t = s.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'");
        // 压缩多余空白
        return t.replaceAll("\\s+", " ").trim();
    }

    // ==================== 工具方法 ====================

    private String buildSuccessResponse(String query, List<SearchResult> items) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("query", query);
        result.put("result_count", items.size());
        ArrayNode results = result.putArray("results");
        for (SearchResult item : items) {
            ObjectNode r = results.addObject();
            r.put("title", item.title);
            r.put("snippet", item.snippet);
            r.put("url", item.url);
        }
        return mapper.writeValueAsString(result);
    }

    private String error(String message) throws Exception {
        System.out.println("[SEARCH] ✗ " + message);
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }

    // 从一个 JSON 对象中按顺序尝试多个字段名，返回第一个非空文本
    private static String firstNonEmpty(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            String v = node.path(name).asText("");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static class SearchResult {
        final String title;
        final String snippet;
        final String url;
        SearchResult(String title, String snippet, String url) {
            this.title = title;
            this.snippet = snippet;
            this.url = url;
        }
    }
}