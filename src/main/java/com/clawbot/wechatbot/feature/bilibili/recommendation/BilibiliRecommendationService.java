package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;

/**
 * 每日高分推荐的公共业务接口。
 *
 * <p>由推荐模块实现，微信交互模块只能依赖本接口，不得直接访问推荐仓储。
 */
public interface BilibiliRecommendationService {
    RecommendationResult recommend(
        String wechatUserId, ContentType contentType, int count);

    RecommendationResult recommend(
        String wechatUserId, ContentType contentType, int count, String tag);

    RecommendationResult refresh(
        String wechatUserId, ContentType contentType, int count);

    RecommendedContent findPendingItem(String wechatUserId, int itemNumber);

    void markWatched(String wechatUserId, int itemNumber);

    void markDisliked(String wechatUserId, int itemNumber);
}
