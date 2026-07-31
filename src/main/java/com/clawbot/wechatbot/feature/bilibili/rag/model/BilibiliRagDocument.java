package com.clawbot.wechatbot.feature.bilibili.rag.model;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.util.Set;

/** 面向 RAG 检索和 Prompt 组装的作品文档。 */
public record BilibiliRagDocument(
    ContentType contentType,
    String contentId,
    String seasonId,
    String title,
    String description,
    Set<String> genres,
    Double rating,
    Long viewCount,
    String pageUrl,
    String latestEpisodeTitle,
    Integer latestEpisodeNumber,
    boolean finished
) {
    public static BilibiliRagDocument from(BilibiliContent content) {
        return new BilibiliRagDocument(
            content.getContentType(),
            content.getContentId(),
            content.getSeasonId(),
            content.getTitle(),
            content.getDescription(),
            Set.copyOf(content.getGenres()),
            content.getRating(),
            content.getViewCount(),
            content.getPageUrl(),
            content.getLatestEpisodeTitle(),
            content.getLatestEpisodeNumber(),
            content.isFinished());
    }
}
