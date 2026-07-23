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
        String query = arguments == null ? "" : arguments.path("query").asText("").trim();
        int count = arguments == null ? DEFAULT_COUNT
            : arguments.path("count").asInt(DEFAULT_COUNT);
        if (count < 1) count = 1;
        if (count > 20) count = 20;

        if (query.isEmpty()) {
            return error("搜索关键词 query 不能为空");
        }

        if (!apiKey.isEmpty()) {
            try {
                String result = searchByBochaApi(query, count);
                if (result != null) return result;
            } catch (Exception ignored) {
            }
        }

        return searchByBing(query, count);
    }

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

        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            return null;
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode webPages = root.path("webPages").path("value");
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
                    break;
                }
            }
        }

        List<SearchResult> items = new ArrayList<>();
        if (webPages.isArray()) {
            for (JsonNode item : webPages) {
                if (items.size() >= count) break;
                String title = firstNonEmpty(item, "name", "title");
                String snippet = firstNonEmpty(item, "snippet", "description", "summary", "text");
                String link = firstNonEmpty(item, "url", "link", "href");
                if (title.isEmpty() && snippet.isEmpty()) continue;
                items.add(new SearchResult(title, snippet, link));
            }
        }
        if (items.isEmpty()) {
            return null;
        }

        return buildSuccessResponse(query, items);
    }

    private String searchByBing(String query, int count) throws Exception {
        String url = BING_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .GET()
            .build();

        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            return error("必应搜索返回 HTTP " + response.statusCode());
        }

        List<SearchResult> items = parseBingHtml(response.body(), count);
        if (items.isEmpty()) {
            return error("必应搜索未能解析到有效结果，请尝试其他关键词");
        }

        return buildSuccessResponse(query, items);
    }

    private static List<SearchResult> parseBingHtml(String html, int count) {
        List<SearchResult> results = new ArrayList<>();
        Matcher blockMatcher = BING_ALGO_BLOCK.matcher(html);
        while (blockMatcher.find() && results.size() < count) {
            String block = blockMatcher.group(1);

            String title = "";
            String link = "";
            Matcher titleMatcher = BING_TITLE.matcher(block);
            if (titleMatcher.find()) {
                link = titleMatcher.group(1).trim();
                title = stripHtmlTags(titleMatcher.group(2)).trim();
            }

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
        String t = s.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }

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
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }

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