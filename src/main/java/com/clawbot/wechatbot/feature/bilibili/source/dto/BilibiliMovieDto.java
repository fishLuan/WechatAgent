package com.clawbot.wechatbot.feature.bilibili.source.dto;

/** B 站电影外部快照。 */
public record BilibiliMovieDto(
    String mediaId,
    String seasonId,
    String title,
    Double rating,
    String pageUrl
) {
}
