package com.clawbot.wechatbot.scheduler.task.impl;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.clawbot.wechatbot.scheduler.task.ScheduledTaskContentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时任务类型的 B 站推荐推送 Provider。
 * 到点后调用推荐服务生成内容并通过微信发送，实现
 * 「9点29分推送2个电影」这类定时 + B站推送的组合能力。
 *
 * <p>paramsJson 支持：content_type（BANGUMI/SERIES/MOVIE，默认 MOVIE）、count（默认 1）。</p>
 */
@Component
public class BilibiliPushContentProvider implements ScheduledTaskContentProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Lazy
    private final BilibiliRecommendationService recommendationService;

    public BilibiliPushContentProvider(@Lazy BilibiliRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.BILIBILI_PUSH;
    }

    @Override
    public String provideContent(String userId, String paramsJson) throws Exception {
        ContentType contentType = ContentType.MOVIE;
        int count = 1;
        if (paramsJson != null && !paramsJson.isBlank()) {
            JsonNode node = MAPPER.readTree(paramsJson);
            if (node != null && node.isObject()) {
                String typeStr = node.path("content_type").asText("");
                if (!typeStr.isBlank()) {
                    try {
                        contentType = ContentType.valueOf(typeStr.trim().toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                        // 未知类型用默认电影
                    }
                }
                int c = node.path("count").asInt(1);
                if (c > 0 && c <= 10) count = c;
            }
        }

        RecommendationResult result = recommendationService.recommend(userId, contentType, count);
        List<RecommendedContent> items = result.items();
        if (items == null || items.isEmpty()) {
            return "【B站推送】暂时没有找到合适的" + typeName(contentType) + "推荐（候选池可能还在抓取中，稍后再试）。";
        }

        String typeLabel = typeName(contentType);
        StringBuilder sb = new StringBuilder("【B站推送】为你推荐 " + count + " 部" + typeLabel + "：\n");
        for (int i = 0; i < items.size(); i++) {
            RecommendedContent c = items.get(i);
            sb.append("\n").append(i + 1).append(". ").append(c.title());
            if (c.rating() != null) {
                sb.append("  ⭐").append(String.format("%.1f", c.rating()));
            }
            if (c.genres() != null && !c.genres().isEmpty()) {
                sb.append("  「").append(String.join(" / ", c.genres())).append("」");
            }
            if (c.latestEpisodeTitle() != null && !c.latestEpisodeTitle().isBlank()) {
                sb.append("\n   最新：").append(c.latestEpisodeTitle());
            }
            if (c.recommendationReason() != null && !c.recommendationReason().isBlank()) {
                sb.append("\n   推荐理由：").append(c.recommendationReason());
            }
            if (c.pageUrl() != null && !c.pageUrl().isBlank()) {
                sb.append("\n   链接：").append(c.pageUrl());
            }
        }
        return sb.toString();
    }

    private String typeName(ContentType type) {
        return switch (type) {
            case BANGUMI -> "动漫";
            case SERIES -> "剧集";
            case MOVIE -> "电影";
            default -> type.name();
        };
    }
}
