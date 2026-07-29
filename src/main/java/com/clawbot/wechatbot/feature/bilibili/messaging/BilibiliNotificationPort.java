package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;

/**
 * 推荐和订阅模块向消息模块发送通知的公共端口。
 *
 * <p>具体的微信格式化和发送逻辑由消息模块实现。
 */
public interface BilibiliNotificationPort {
    void notifyEpisodeUpdate(
        String wechatUserId, EpisodeUpdateNotification notification);

    void notifyDailyRecommendation(
        String wechatUserId, RecommendationResult recommendation);
}
