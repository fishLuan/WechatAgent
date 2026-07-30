package com.clawbot.wechatbot.intent;

import java.util.LinkedHashMap;
import java.util.Map;

public record IntentResult(
    IntentType type,
    double confidence,
    Map<String, String> slots
) {
    public IntentResult {
        if (type == null) type = IntentType.GENERAL_CHAT;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        slots = slots == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(slots));
    }

    public String slot(String name) {
        return slots.get(name);
    }

    public boolean isBilibiliIntent() {
        return switch (type) {
            case BILIBILI_SUBSCRIBE_URL,
                 BILIBILI_SUBSCRIBE_INDEX,
                 BILIBILI_SUBSCRIBE_TITLE,
                 BILIBILI_SEARCH_TITLE,
                 BILIBILI_MARK_TITLE,
                 BILIBILI_RECOMMEND -> true;
            default -> false;
        };
    }
}
