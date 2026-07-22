package com.clawbot.wechatbot.tools.tiannewstool;

import com.clawbot.wechatbot.tools.FunctionTool;
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

/** 天行数据综合新闻查询工具。支持关键词搜索和数量控制。 */
public class TianNewsTool implements FunctionTool {
    private static final String API_URL = "https://apis.tianapi.com/generalnews/index";

    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout;

    public TianNewsTool(String apiKey) {
        this(apiKey, new ObjectMapper(), HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(15));
    }

    TianNewsTool(String apiKey, ObjectMapper mapper, HttpClient http, Duration timeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.mapper = mapper;
        this.http = http;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "get_news";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description", "查询最新新闻资讯、热点事件、社会大事。支持按关键词搜索。当用户提到新闻、大事、热点、发生了什么、最近有什么事、头条等话题时，必须调用此工具获取实时新闻数据。返回新闻标题、摘要和链接。");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        properties.putObject("keyword")
            .put("type", "string")
            .put("description", "搜索关键词，例如：科技、体育、娱乐。不传则返回最新综合新闻。");

        properties.putObject("num")
            .put("type", "integer")
            .put("description", "返回新闻条数，1-50，默认 5");

        parameters.putArray("required");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (apiKey.isEmpty()) {
            return error("天行 API Key 未配置，请设置 TIANAPI_API_KEY");
        }

        String keyword = arguments == null ? "" : arguments.path("keyword").asText("").trim();
        int num = arguments == null ? 5 : arguments.path("num").asInt(5);
        if (num < 1) num = 1;
        if (num > 50) num = 50;

        StringBuilder url = new StringBuilder(API_URL)
            .append("?key=").append(encode(apiKey))
            .append("&num=").append(num)
            .append("&form=1");
        if (!keyword.isEmpty()) {
            url.append("&word=").append(encode(keyword));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .timeout(timeout)
            .GET()
            .build();

        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            return error("天行新闻接口返回 HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        if (root.path("code").asInt() != 200) {
            return error("天行新闻查询失败：" + root.path("msg").asText("未知错误"));
        }

        JsonNode apiResult = root.path("result");
        JsonNode newsList = apiResult.path("list");
        if (!newsList.isArray() || newsList.isEmpty()) {
            return error("未找到相关新闻");
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode item : newsList) {
            String title = item.path("title").asText("");
            String description = item.path("description").asText("");
            String source = item.path("source").asText("");
            String ctime = item.path("ctime").asText("");
            String link = item.path("url").asText("");

            result.append("📰 ").append(title).append("\n");
            if (!description.isEmpty()) {
                result.append("   ").append(description).append("\n");
            }
            result.append("   来源：").append(source).append(" | ").append(ctime).append("\n");
            if (!link.isEmpty()) {
                result.append("   链接：").append(link).append("\n");
            }
            result.append("\n");
        }

        return result.toString().trim();
    }

    private String error(String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
