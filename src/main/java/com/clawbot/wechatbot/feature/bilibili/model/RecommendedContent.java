package com.clawbot.wechatbot.feature.bilibili.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** 面向微信交互层的只读推荐条目，不暴露 MongoDB 实体。 */
public record RecommendedContent(
    ContentType contentType,
    String contentId,
    String seasonId,
    String title,
    Double rating,
    Set<String> genres,
    String pageUrl,
    String latestEpisodeTitle,
    String recommendationReason
) {
    public RecommendedContent {
        if (contentType == null) {
            throw new IllegalArgumentException("contentType 不能为空");
        }
        contentId = requireText(contentId, "contentId");
        title = requireText(title, "title");
        genres = genres == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(genres));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
