package com.clawbot.wechatbot.service.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/** DashScope 不同多模态模型响应格式的集中解析器。 */
public final class DashScopeResponseParser {
    private DashScopeResponseParser() {}

    public static String extractMessageText(JsonNode root) {
        JsonNode content = root.path("output").path("choices").path(0).path("message").path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    public static String extractImageUrl(JsonNode root) {
        String url = root.path("output").path("result").path(0).path("url").asText("");
        return url.isBlank() ? findFirstHttpUrl(root) : url;
    }

    public static String extractAudioUrl(JsonNode root) {
        String url = root.path("output").path("audio").path("url").asText("");
        return url.isBlank() ? findFirstHttpUrl(root) : url;
    }

    private static String findFirstHttpUrl(JsonNode node) {
        if (node == null) return "";
        if (node.isTextual()) {
            String value = node.asText();
            return value.startsWith("http://") || value.startsWith("https://") ? value : "";
        }
        if (node.isContainerNode()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                String value = findFirstHttpUrl(fields.next().getValue());
                if (!value.isBlank()) return value;
            }
            if (node.isArray()) {
                for (JsonNode child : node) {
                    String value = findFirstHttpUrl(child);
                    if (!value.isBlank()) return value;
                }
            }
        }
        return "";
    }
}
