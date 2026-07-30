package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import org.springframework.stereotype.Component;

@Component
public final class BilibiliNotificationPortImpl implements BilibiliNotificationPort {
    private final WeChatOutboundGateway gateway;
    private final WeChatSessionRegistry sessions;

    public BilibiliNotificationPortImpl(
        WeChatOutboundGateway gateway,
        WeChatSessionRegistry sessions
    ) {
        this.gateway = gateway;
        this.sessions = sessions;
    }

    @Override
    public void notifyEpisodeUpdate(
        String wechatUserId, EpisodeUpdateNotification notification
    ) {
        requireDeliverable(wechatUserId, notification);
        gateway.sendText(
            wechatUserId, BilibiliMessageFormatter.formatEpisodeUpdate(notification));
    }

    @Override
    public void notifyDailyRecommendation(
        String wechatUserId, RecommendationResult recommendation
    ) {
        requireDeliverable(wechatUserId, recommendation);
        gateway.sendText(
            wechatUserId, BilibiliMessageFormatter.formatRecommendation(recommendation));
    }

    private void requireDeliverable(String userId, Object payload) {
        if (payload == null) throw new IllegalArgumentException("推送内容不能为空");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("微信用户 ID 不能为空");
        }
        if (!gateway.isAvailable(userId)) {
            throw new IllegalStateException("微信发送通道当前不可用");
        }
    }
}
