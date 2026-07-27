package com.clawbot.wechatbot.tools.searchonlinetool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索工具 —— 模块对外唯一入口（仅此 public 类）。
 *
 * 内部静态成员（外部通过 WebSearchTool.SearchChannel 访问）：
 *   - SearchChannel：搜索通道抽象接口
 *   - SearchResult：单条搜索结果 POJO
 *
 * 构建器方法已收为本类私有静态方法：
 *   - buildDefinition()  → tool-calling 的 JSON Schema
 *   - buildSuccess() / buildError()  → 工具响应 JSON
 *
 * 通道 fallback 顺序：
 *   1. BochaApiSearchChannel —— 博查 AI API（有 Key 时优先）
 *   2. BingHtmlSearchChannel —— 必应国内版 HTML 抓取（永远兜底）
 */
public class WebSearchTool implements FunctionTool {

    /* ========== 常量 ========== */
    private static final String DEFAULT_ENDPOINT = "https://api.bochaai.com/v1/web-search";
    private static final String TOOL_NAME = "web_search";
    private static final int DEFAULT_COUNT = 5;

    /* ========== 字段 ========== */
    private final ObjectMapper mapper;
    private final List<SearchChannel> channels;

    public WebSearchTool(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, 30, 60);
    }

    public WebSearchTool(String apiKey, String endpoint, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        String trimmedEndpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint;

        this.mapper = new ObjectMapper();
        SearchHttpClient http = new SearchHttpClient(connectTimeoutSeconds, requestTimeoutSeconds);

        this.channels = new ArrayList<>(2);
        this.channels.add(new BochaApiSearchChannel(trimmedKey, trimmedEndpoint, http, this.mapper));
        this.channels.add(new BingHtmlSearchChannel(http));
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public JsonNode definition() {
        return buildDefinition(mapper);
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String query = arguments == null ? "" : arguments.path("query").asText("").trim();
        int count = arguments == null ? DEFAULT_COUNT
            : arguments.path("count").asInt(DEFAULT_COUNT);
        if (count < 1) count = 1;
        if (count > 20) count = 20;

        if (query.isEmpty()) {
            return buildError(mapper, "搜索关键词 query 不能为空");
        }
        return executePipeline(query, count);
    }

    /* ========== 通道 fallback 主流程 ========== */
    private String executePipeline(String query, int count) throws Exception {
        int lastIndex = channels.size() - 1;
        for (int i = 0; i < channels.size(); i++) {
            SearchChannel ch = channels.get(i);
            try {
                System.out.println("[TOOL:web_search] 尝试通道 (" + (i + 1) + "/" + channels.size() + "): " + ch.name());
                List<SearchResult> items = ch.search(query, count);
                if (items != null && !items.isEmpty()) {
                    System.out.println("[TOOL:web_search] 命中 " + items.size() + " 条结果 <- " + ch.name());
                    return buildSuccess(mapper, query, items);
                }
                System.out.println("[TOOL:web_search] 无结果，fallback 下一个通道");
            } catch (Exception e) {
                if (i == lastIndex) {
                    String msg = e.getMessage();
                    if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
                    System.out.println("[TOOL:web_search] 最后通道 " + ch.name() + " 失败: " + msg);
                    return buildError(mapper, ch.name() + "失败：" + msg);
                }
                System.out.println("[TOOL:web_search] 通道 " + ch.name() + " 异常，fallback 下一个: " + e.getMessage());
            }
        }
        return buildError(mapper, "所有搜索通道均未返回有效结果，请更换关键词重试");
    }

    /* ========== definition 构建器 ========== */
    private static JsonNode buildDefinition(ObjectMapper mapper) {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", TOOL_NAME);
        function.put("description",
            "联网搜索工具，用于查询你不知道的事实和实时数据。"
            + "当没有专用工具且你无法准确回答的实时或事实性问题时，请务必使用本工具。"
            + "注意：天气请使用 get_weather 专用工具。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query")
            .put("type", "string")
            .put("description", "搜索关键词，简洁准确的中文关键词");
        properties.putObject("count")
            .put("type", "integer")
            .put("description", "返回的搜索结果条数，默认 " + DEFAULT_COUNT + "，一般 3-8 条足够");

        ArrayNode required = parameters.putArray("required");
        required.add("query");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    /* ========== 响应 JSON 构建器 ========== */
    private static String buildSuccess(ObjectMapper mapper, String query, List<SearchResult> items) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("query", query);
        result.put("result_count", items == null ? 0 : items.size());
        ArrayNode arr = result.putArray("results");
        if (items != null) {
            for (SearchResult it : items) {
                ObjectNode r = arr.addObject();
                r.put("title", it.title);
                r.put("snippet", it.snippet);
                r.put("url", it.url);
            }
        }
        return mapper.writeValueAsString(result);
    }

    private static String buildError(ObjectMapper mapper, String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message == null ? "未知错误" : message);
        return mapper.writeValueAsString(result);
    }

    /* ====================================================================
     *  静态内部接口 & 内部类（static nested，消除多顶级类"奇怪感"）
     *  外部引用形式：
     *    WebSearchTool.SearchChannel
     *    WebSearchTool.SearchResult
     * ==================================================================== */

    /**
     * 搜索通道抽象接口（仅供 searchonlinetool 内部使用）。
     * 返回 null / 空列表，或抛异常 = 本通道失败，触发 fallback。
     */
    public interface SearchChannel {
        String name();
        List<SearchResult> search(String query, int count) throws Exception;
    }

    /** 单条搜索结果 POJO */
    public static class SearchResult {
        public final String title;
        public final String snippet;
        public final String url;

        public SearchResult(String title, String snippet, String url) {
            this.title = title == null ? "" : title;
            this.snippet = snippet == null ? "" : snippet;
            this.url = url == null ? "" : url;
        }
    }
}