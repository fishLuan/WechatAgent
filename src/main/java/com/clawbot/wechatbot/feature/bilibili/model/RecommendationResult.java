package com.clawbot.wechatbot.feature.bilibili.model;

import java.time.Instant;
import java.util.List;

/** 一次推荐请求的稳定返回对象。 */
public record RecommendationResult(
    String wechatUserId,
    ContentType contentType,
    List<RecommendedContent> items,
    Instant generatedAt
) {
    public RecommendationResult {
        wechatUserId = requireText(wechatUserId, "wechatUserId");
        if (contentType == null) {
            throw new IllegalArgumentException("contentType 不能为空");
        }
        items = items == null ? List.of() : List.copyOf(items);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
