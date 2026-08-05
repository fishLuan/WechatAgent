package com.clawbot.wechatbot.feature.document.application;

import com.clawbot.wechatbot.feature.document.messaging.WordDocumentCommandParser.CommandType;
import com.clawbot.wechatbot.feature.document.messaging.WordDocumentCommandParser.ParsedCommand;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 将自由表达的 Word 修改要求规划为编辑器允许执行的白名单命令。 */
@Component
public class WordDocumentNaturalLanguagePlanner {
    private static final int MAX_COMMANDS = 20;
    private static final int MAX_VALUE_LENGTH = 1000;
    private static final int MAX_DOCUMENT_CONTEXT = 4000;
    private static final String PROMPT = """
        你是 Word 文档修改指令规划器。只输出严格 JSON，不要解释，不要 Markdown。
        输出格式：
        {"status":"READY","clarification":"","commands":[
          {"type":"BODY_FONT_FAMILY","first":"宋体","second":""}
        ]}

        可用 type：REPLACE, APPEND, PREPEND, DELETE_TEXT, RENAME, EXPORT,
        CLEAR_CONTENT, FONT_SIZE, FONT_FAMILY, BOLD, UNBOLD,
        FORMAT_LARGER_CENTER, FORMAT_LARGER, FORMAT_SMALLER, FORMAT_CENTER,
        ALIGN_LEFT, ALIGN_RIGHT, ALIGN_BOTH, FIRST_LINE_INDENT,
        CLEAR_FIRST_LINE_INDENT, LINE_SPACING, PARAGRAPH_SPACING_LARGER,
        DELETE_EMPTY_LINES, SET_TITLE, FIRST_LINE_AS_TITLE, BEAUTIFY,
        TITLE_FONT_SIZE, BODY_FONT_SIZE, TITLE_FONT_FAMILY, BODY_FONT_FAMILY,
        TITLE_BOLD, BODY_BOLD, BODY_UNBOLD, TITLE_CENTER, BODY_CENTER,
        BODY_ALIGN_BOTH, BODY_FIRST_LINE_INDENT, SPACING_BEFORE, SPACING_AFTER,
        ADD_HEADING1, ADD_HEADING2, ADD_PARAGRAPH, ADD_PAGE_BREAK,
        DELETE_PARAGRAPH_CONTAINING, VIEW,
        PARAGRAPH_FONT_SIZE, PARAGRAPH_FONT_FAMILY, PARAGRAPH_BOLD,
        PARAGRAPH_UNBOLD, PARAGRAPH_ALIGN, MATCHING_PARAGRAPH_FONT_SIZE,
        MATCHING_PARAGRAPH_FONT_FAMILY, MATCHING_PARAGRAPH_BOLD,
        MATCHING_PARAGRAPH_UNBOLD, MATCHING_PARAGRAPH_ALIGN。

        参数规则：
        - REPLACE：first=旧内容，second=新内容。
        - 字号、字体、行距、间距和文本类操作使用 first。
        - PARAGRAPH_*：first=非空段落序号（从1开始），second=字号、字体或
          LEFT/RIGHT/CENTER/BOTH；加粗类 second 留空。
        - MATCHING_PARAGRAPH_*：first=用于定位段落的原文关键词，second=字号、
          字体或 LEFT/RIGHT/CENTER/BOTH；加粗类 second 留空。
        - 无参数操作 first 和 second 留空。
        - “正式一点/好看一点/规范一下”通常映射 BEAUTIFY。
        - 严格保留用户提供的文字，不扩写、不创作、不添加用户没要求的操作。
        - 按用户表达顺序输出，不遗漏明确要求。
        - 只有含义或必要参数确实无法确定时，status=CLARIFY，commands=[]，
          clarification 写一个简短问题。不要猜测。
        - 不得输出可用 type 之外的操作。
        """;

    private final DeepSeekClient client;
    private final ObjectMapper mapper;

    public WordDocumentNaturalLanguagePlanner(DeepSeekClient client) {
        this.client = client;
        this.mapper = client.mapper();
    }

    public PlanResult plan(String request, String documentText) throws Exception {
        if (!client.isConfigured()) return PlanResult.unavailable();
        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", PROMPT));
        String context = documentText == null ? "" : documentText;
        if (context.length() > MAX_DOCUMENT_CONTEXT) {
            context = context.substring(0, MAX_DOCUMENT_CONTEXT);
        }
        messages.add(message("user", "当前文档内容：\n" + context
            + "\n\n用户修改要求：\n" + request));
        JsonNode response = client.chat(messages, mapper.createArrayNode(), 0.0);
        String content = response.path("choices").path(0).path("message")
            .path("content").asText("");
        return parseModelContent(content);
    }

    PlanResult parseModelContent(String content) {
        try {
            JsonNode root = mapper.readTree(extractJson(content));
            String status = root.path("status").asText("").trim()
                .toUpperCase(Locale.ROOT);
            if ("CLARIFY".equals(status)) {
                String clarification = bounded(root.path("clarification").asText(""));
                return clarification.isBlank()
                    ? PlanResult.invalid("需要补充更明确的修改范围或参数。")
                    : PlanResult.clarify(clarification);
            }
            JsonNode commandNodes = root.path("commands");
            if (!"READY".equals(status) || !commandNodes.isArray()
                || commandNodes.isEmpty() || commandNodes.size() > MAX_COMMANDS) {
                return PlanResult.invalid("没有得到可执行的 Word 操作。 ");
            }
            List<ParsedCommand> commands = new ArrayList<>();
            for (JsonNode node : commandNodes) {
                CommandType type = parseType(node.path("type").asText(""));
                if (!isAllowed(type)) {
                    return PlanResult.invalid("模型返回了不支持的 Word 操作。");
                }
                String first = nullableBounded(node.path("first").asText(""));
                String second = nullableBounded(node.path("second").asText(""));
                commands.add(new ParsedCommand(type, first, second));
            }
            return PlanResult.ready(List.copyOf(commands));
        } catch (Exception error) {
            return PlanResult.invalid("自然语言修改要求解析失败。");
        }
    }

    private CommandType parseType(String value) {
        try {
            return CommandType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return CommandType.UNKNOWN;
        }
    }

    private boolean isAllowed(CommandType type) {
        return type != CommandType.UNKNOWN && type != CommandType.HELP
            && type != CommandType.CLEAR;
    }

    private String nullableBounded(String value) {
        String normalized = bounded(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String bounded(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= MAX_VALUE_LENGTH
            ? normalized : normalized.substring(0, MAX_VALUE_LENGTH);
    }

    private String extractJson(String content) {
        if (content == null) return "{}";
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end >= start ? content.substring(start, end + 1) : "{}";
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    public record PlanResult(
        Status status,
        List<ParsedCommand> commands,
        String message
    ) {
        static PlanResult ready(List<ParsedCommand> commands) {
            return new PlanResult(Status.READY, commands, "");
        }

        static PlanResult clarify(String message) {
            return new PlanResult(Status.CLARIFY, List.of(), message);
        }

        static PlanResult unavailable() {
            return new PlanResult(Status.UNAVAILABLE, List.of(), "大模型未配置。");
        }

        static PlanResult invalid(String message) {
            return new PlanResult(Status.INVALID, List.of(), message);
        }
    }

    public enum Status {
        READY,
        CLARIFY,
        UNAVAILABLE,
        INVALID
    }
}
