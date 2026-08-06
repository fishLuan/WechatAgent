package com.clawbot.wechatbot.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Converts internal structured task output into text suitable for end users. */
public final class UserFacingResultFormatter {

    private static final List<String> PREFERRED_TEXT_FIELDS = List.of(
        "display_text", "message", "description", "text", "weather_text",
        "weather_info", "route_info", "reply", "poem", "summary", "result"
    );

    private final ObjectMapper mapper;

    public UserFacingResultFormatter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String format(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";
        String trimmed = unwrapJsonCodeFence(rawText.trim());
        if (!looksLikeJson(trimmed)) return rawText;
        try {
            JsonNode root = mapper.readTree(trimmed);
            if (root == null || root.isMissingNode()) return rawText;
            String preferred = preferredText(root);
            return preferred == null ? readable(root) : preferred;
        } catch (Exception ignored) {
            return rawText;
        }
    }

    public List<String> formatAll(List<String> texts) {
        return texts.stream().map(this::format).toList();
    }

    private boolean looksLikeJson(String text) {
        return (text.startsWith("{") && text.endsWith("}"))
            || (text.startsWith("[") && text.endsWith("]"));
    }

    private String unwrapJsonCodeFence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```")) return text;
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) return text;
        String language = text.substring(3, firstLineEnd).trim();
        if (!language.isEmpty() && !language.equalsIgnoreCase("json")) return text;
        return text.substring(firstLineEnd + 1, text.length() - 3).trim();
    }

    private String preferredText(JsonNode root) {
        if (!root.isObject()) return null;
        for (String field : PREFERRED_TEXT_FIELDS) {
            JsonNode value = root.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String readable(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual() || node.isValueNode()) return node.asText();
        if (node.isArray()) {
            StringBuilder output = new StringBuilder();
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) output.append('\n');
                output.append(index + 1).append(". ").append(readable(node.get(index)));
            }
            return output.toString();
        }
        StringBuilder output = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (output.length() > 0) output.append('\n');
            output.append(field.getKey()).append("：").append(readable(field.getValue()));
        }
        return output.toString();
    }
}
