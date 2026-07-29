package com.clawbot.wechatbot.tools.searchweathertool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 高德开放平台天气查询工具。支持实时天气和天气预报。 */
public class AmapWeatherTool implements FunctionTool {
    private static final String DEFAULT_ENDPOINT = "https://restapi.amap.com/v3/weather/weatherInfo";

    private final String apiKey;
    private final String endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public AmapWeatherTool(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), Duration.ofSeconds(15));
    }

    public AmapWeatherTool(String apiKey, String endpoint, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this(apiKey, endpoint, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build(),
            new ObjectMapper(), Duration.ofSeconds(requestTimeoutSeconds));
    }

    AmapWeatherTool(String apiKey, String endpoint, HttpClient http, ObjectMapper mapper,
                    Duration requestTimeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description", "查询中国城市的实时天气或未来天气预报。城市参数可使用中文城市名、区县名或高德 adcode。");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("city")
            .put("type", "string")
            .put("description", "城市、区县名称或 adcode，例如：北京、杭州市、西湖区、110000");
        ObjectNode extensionsProperty = properties.putObject("extensions");
        extensionsProperty.put("type", "string");
        extensionsProperty.set("enum", mapper.createArrayNode().add("base").add("all"));
        extensionsProperty.put("description", "base 表示实时天气，all 表示天气预报；默认 base");
        parameters.putArray("required").add("city");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (apiKey.isEmpty()) {
            return error("高德天气 API Key 未配置，请设置 AMAP_WEATHER_API_KEY");
        }
        String city = arguments == null ? "" : arguments.path("city").asText("").trim();
        String extensions = arguments == null ? "base" : arguments.path("extensions").asText("base");
        if (city.isEmpty()) return error("city 参数不能为空");
        if (!"base".equals(extensions) && !"all".equals(extensions)) extensions = "base";

        String url = endpoint + "?key=" + encode(apiKey)
            + "&city=" + encode(city) + "&extensions=" + extensions + "&output=JSON";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(requestTimeout).GET().build();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            return error("高德天气接口返回 HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        if (!"1".equals(root.path("status").asText())) {
            return error("高德天气查询失败：" + root.path("info").asText("未知错误"));
        }
        // 保留高德的结构化结果，让模型自行组织自然语言；不把 key 回传给模型。
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("query_city", city);
        result.put("type", extensions);
        if ("all".equals(extensions)) result.set("forecasts", root.path("forecasts"));
        else result.set("lives", root.path("lives"));
        return mapper.writeValueAsString(result);
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
