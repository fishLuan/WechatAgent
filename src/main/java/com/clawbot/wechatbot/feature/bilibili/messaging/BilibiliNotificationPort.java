package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;

public interface BilibiliNotificationPort {
    void notifyEpisodeUpdate(
        String wechatUserId, EpisodeUpdateNotification notification);

    void notifyDailyRecommendation(
        String wechatUserId, RecommendationResult recommendation);
}
