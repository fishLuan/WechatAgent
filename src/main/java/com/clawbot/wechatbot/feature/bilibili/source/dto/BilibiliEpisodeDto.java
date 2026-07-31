package com.clawbot.wechatbot.feature.bilibili.source.dto;

import java.time.Instant;

/** B 站剧集外部快照。 */
public record BilibiliEpisodeDto(
    String episodeId,
    String title,
    Integer episodeNumber,
    String pageUrl,
    Instant pubTime
) {
    public BilibiliEpisodeDto(String episodeId, String title, Integer episodeNumber, String pageUrl) {
        this(episodeId, title, episodeNumber, pageUrl, null);
    }
}
