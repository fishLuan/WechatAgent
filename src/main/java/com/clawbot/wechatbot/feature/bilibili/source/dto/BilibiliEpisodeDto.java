package com.clawbot.wechatbot.feature.bilibili.source.dto;

/** B 站剧集外部快照。 */
public record BilibiliEpisodeDto(
    String episodeId,
    String title,
    Integer episodeNumber,
    String pageUrl
) {
}
