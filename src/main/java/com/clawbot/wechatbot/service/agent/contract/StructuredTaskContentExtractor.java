package com.clawbot.wechatbot.service.agent.contract;

import com.clawbot.wechatbot.service.agent.UserFacingResultFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts user-facing content from verified task inputs and dependency output. */
public final class StructuredTaskContentExtractor {
    private final UserFacingResultFormatter formatter;

    public StructuredTaskContentExtractor(ObjectMapper mapper) {
        this.formatter = new UserFacingResultFormatter(mapper);
    }

    public String extract(JsonNode resolvedInput, String dependencyText) {
        if (dependencyText != null && !dependencyText.isBlank()) {
            String[] blocks = dependencyText.split(
                "(?m)^【[^\\r\\n]+】\\s*(?:\\r?\\n)?");
            StringBuilder extracted = new StringBuilder();
            for (String block : blocks) {
                if (block.isBlank()) continue;
                String formatted = formatter.format(block.trim()).trim();
                if (formatted.isBlank()) continue;
                if (extracted.length() > 0) extracted.append("\n\n");
                extracted.append(formatted);
            }
            String dependency = extracted.toString();
            if (!dependency.isBlank()) return dependency;
        }
        if (resolvedInput == null || !resolvedInput.isObject()) return "";
        for (String field : new String[] {
            "display_text", "message", "description", "content", "text",
            "weather_text", "weather_info", "route_info", "reply", "poem",
            "summary", "result", "value"
        }) {
            JsonNode value = resolvedInput.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
            if (value.isContainerNode() && !value.isEmpty()) {
                return formatter.format(value.toString()).trim();
            }
        }
        return "";
    }
}
