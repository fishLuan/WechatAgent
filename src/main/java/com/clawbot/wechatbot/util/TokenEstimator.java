package com.clawbot.wechatbot.util;

import com.fasterxml.jackson.databind.JsonNode;

public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(JsonNode messages) {
        if (messages == null || !messages.isArray()) return 0;
        int total = 0;
        for (JsonNode msg : messages) {
            total += estimateMessage(msg);
        }
        return total;
    }

    private static int estimateMessage(JsonNode msg) {
        int chars = 0;
        chars += msg.path("role").asText().length();
        chars += msg.path("content").asText().length();
        JsonNode toolCalls = msg.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                chars += call.path("function").path("name").asText().length();
                chars += call.path("function").path("arguments").asText().length();
            }
        }
        return chars / 3;
    }
}
