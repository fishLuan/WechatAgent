package com.clawbot.wechatbot.tools.webPageTool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.FunctionToolLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** OpenAI Function Calling 范式的网页正文抓取工具。 */
public class WebPageExtractTool implements FunctionTool {
    private final WebPageExtractClient client;
    private final ObjectMapper mapper;
    private final int defaultMaxBodyChars;
    private final FunctionToolLogger logger;

    public WebPageExtractTool(int connectTimeoutSeconds, int requestTimeoutSeconds, int defaultMaxBodyChars) {
        this(new WebPageExtractClient(connectTimeoutSeconds, requestTimeoutSeconds, defaultMaxBodyChars),
            new ObjectMapper(), defaultMaxBodyChars, FunctionToolLogger.NOOP);
    }

    public WebPageExtractTool(WebPageExtractClient client, ObjectMapper mapper, int defaultMaxBodyChars) {
        this(client, mapper, defaultMaxBodyChars, FunctionToolLogger.NOOP);
    }

    public WebPageExtractTool(WebPageExtractClient client, ObjectMapper mapper, int defaultMaxBodyChars,
                              FunctionToolLogger logger) {
        this.client = client == null
            ? new WebPageExtractClient(10, 15, defaultMaxBodyChars)
            : client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.defaultMaxBodyChars = defaultMaxBodyChars;
        this.logger = logger == null ? FunctionToolLogger.NOOP : logger;
    }

    @Override
    public String name() {
        return "extract_web_page";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "当用户要求阅读、抓取、总结、提取网页正文，或需要了解 URL 链接里的实时内容时调用。"
                + "输入网页 URL，返回标题、描述和清理后的正文。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("url")
            .put("type", "string")
            .put("description", "需要抓取的网页 URL，必须是 http 或 https 链接。");
        properties.putObject("max_body_chars")
            .put("type", "integer")
            .put("description", "最多返回多少个正文字符；默认使用系统配置。");
        parameters.putArray("required").add("url");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String url = arguments == null ? "" : arguments.path("url").asText("");
        int maxBodyChars = arguments == null ? defaultMaxBodyChars : arguments.path("max_body_chars").asInt(defaultMaxBodyChars);
        logger.log("[WEBPAGE_TOOL] executing url=" + url + ", maxBodyChars=" + maxBodyChars);

        WebPageExtractResult result = client.extract(new WebPageExtractRequest(url, maxBodyChars));
        ObjectNode output = mapper.createObjectNode();
        output.put("success", result.isSuccess());
        output.put("url", result.getUrl());
        if (result.isSuccess()) {
            output.put("final_url", result.getFinalUrl());
            output.put("status_code", result.getStatusCode());
            output.put("title", result.getTitle());
            output.put("description", result.getDescription());
            output.put("body_text", result.getBodyText());
        } else {
            output.put("error", result.getError());
        }
        return mapper.writeValueAsString(output);
    }
}
