package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliContentDto;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliEpisodeDto;

import java.time.Instant;

/** 将采集层 DTO 转换为领域内容模型。 */
final class BilibiliContentMapper {
    BilibiliContent toContent(BilibiliContentDto dto) {
        BilibiliContent content = new BilibiliContent(
            dto.getContentType(), dto.getContentId(), dto.getTitle());
        content.setSeasonId(dto.getSeasonId());
        content.setDescription(dto.getDescription());
        content.setGenres(dto.getGenres());
        content.setRating(dto.getRating());
        content.setViewCount(dto.getViewCount());
        content.setCoverUrl(dto.getCoverUrl());
        content.setPageUrl(dto.getPageUrl());
        BilibiliEpisodeDto latest = dto.getLatestEpisode();
        if (latest != null) {
            content.setLatestEpisodeId(latest.episodeId());
            content.setLatestEpisodeTitle(latest.title());
            content.setLatestEpisodeNumber(latest.episodeNumber());
            content.setLatestEpisodePubTime(latest.pubTime());
        }
        if (dto.getLatestEpisodePubTime() != null) {
            content.setLatestEpisodePubTime(dto.getLatestEpisodePubTime());
        }
        content.setFinished(dto.isFinished());
        content.setLastFetchedAt(Instant.now());
        return content;
    }
}
