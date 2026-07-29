package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import org.springframework.stereotype.Component;

@Component
public class BilibiliNotificationPortImpl implements BilibiliNotificationPort {
    private final WeChatOutboundGateway outboundGateway;
    private final WeChatSessionRegistry sessionRegistry;

    public BilibiliNotificationPortImpl(WeChatOutboundGateway outboundGateway,
                                        WeChatSessionRegistry sessionRegistry) {
        this.outboundGateway = outboundGateway;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void notifyEpisodeUpdate(String wechatUserId, EpisodeUpdateNotification notification) {
        if (!isValidTarget(wechatUserId) || notification == null) {
            return;
        }
        String msg = BilibiliMessageFormatter.formatEpisodeUpdate(notification);
        safeSend(wechatUserId, msg);
    }

    @Override
    public void notifyDailyRecommendation(String wechatUserId, RecommendationResult recommendation) {
        if (!isValidTarget(wechatUserId) || recommendation == null) {
            return;
        }
        String msg = BilibiliMessageFormatter.formatRecommendation(recommendation);
        safeSend(wechatUserId, msg);
    }

    private boolean isValidTarget(String userId) {
        if (userId == null || userId.isBlank()) return false;
        if (!sessionRegistry.isActive(userId)) return false;
        return outboundGateway.isAvailable(userId);
    }

    private void safeSend(String userId, String msg) {
        try {
            outboundGateway.sendText(userId, msg);
        } catch (Exception e) {
            System.err.println("[BILIBILI-NOTIFY] 发送失败 userId=" + userId
                + " err=" + e.getMessage());
        }
    }
}