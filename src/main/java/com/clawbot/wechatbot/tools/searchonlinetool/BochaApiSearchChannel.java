package com.clawbot.wechatbot.tools.searchonlinetool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道A：博查 AI Web Search API（有 Key 时优先使用）。
 * 官网：https://open.bochaai.com
 */
class BochaApiSearchChannel implements WebSearchTool.SearchChannel {

    private static final String DEFAULT_ENDPOINT = "https://api.bochaai.com/v1/web-search";

    private final String apiKey;
    private final String endpoint;
    private final SearchHttpClient http;
    private final ObjectMapper mapper;

    BochaApiSearchChannel(String apiKey, String endpoint,
                          SearchHttpClient http, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "博查AI API";
    }

    @Override
    public List<WebSearchTool.SearchResult> search(String query, int count) throws Exception {
        if (apiKey.isEmpty()) return null;

        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        body.put("count", count);
        body.put("summary", true);
        body.put("freshness", "noLimit");

        String auth = "Bearer " + apiKey;
        String respBody = http.postJson(endpoint, auth, mapper.writeValueAsString(body));
        if (respBody == null) return null;

        JsonNode root = mapper.readTree(respBody);
        JsonNode webPages = root.path("webPages").path("value");
        if (!webPages.isArray() || webPages.size() == 0) {
            String[][] fallbacks = new String[][] {
                {"webPages", "value"},
                {"data", "webPages", "value"},
                {"data", "webPages"},
                {"data", "value"},
                {"webPages"},
                {"results"},
                {"items"}
            };
            for (String[] path : fallbacks) {
                JsonNode tmp = root;
                for (String p : path) tmp = tmp.path(p);
                if (tmp.isArray() && tmp.size() > 0) {
                    webPages = tmp;
                    break;
                }
            }
        }

        List<WebSearchTool.SearchResult> items = new ArrayList<>();
        if (webPages.isArray()) {
            for (JsonNode item : webPages) {
                if (items.size() >= count) break;
                String title = firstNonEmpty(item, "name", "title");
                String snippet = firstNonEmpty(item, "snippet", "description", "summary", "text");
                String link  = firstNonEmpty(item, "url", "link", "href");
                if (title.isEmpty() && snippet.isEmpty()) continue;
                items.add(new WebSearchTool.SearchResult(title, snippet, link));
            }
        }
        return items.isEmpty() ? null : items;
    }

    private static String firstNonEmpty(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            String v = node.path(name).asText("");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }
}