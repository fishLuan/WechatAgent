package com.clawbot.wechatbot.feature.bilibili.model;

import java.time.Instant;

/** 订阅模块发送给通知端口的新剧集消息。 */
public record EpisodeUpdateNotification(
    String subscriptionId,
    String title,
    String episodeId,
    Integer episodeNumber,
    String episodeTitle,
    String episodeUrl,
    Instant detectedAt
) {
    public EpisodeUpdateNotification {
        subscriptionId = requireText(subscriptionId, "subscriptionId");
        title = requireText(title, "title");
        episodeId = requireText(episodeId, "episodeId");
        detectedAt = detectedAt == null ? Instant.now() : detectedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
