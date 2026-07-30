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
        requireValidTarget(wechatUserId, notification);
        String msg = BilibiliMessageFormatter.formatEpisodeUpdate(notification);
        outboundGateway.sendText(wechatUserId.trim(), msg);
    }

    @Override
    public void notifyDailyRecommendation(String wechatUserId, RecommendationResult recommendation) {
        requireValidTarget(wechatUserId, recommendation);
        String msg = BilibiliMessageFormatter.formatRecommendation(recommendation);
        outboundGateway.sendText(wechatUserId.trim(), msg);
    }

    private void requireValidTarget(String userId, Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("通知内容不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("wechatUserId 不能为空");
        }
        String target = userId.trim();
        if (!sessionRegistry.isActive(target)) {
            throw new IllegalStateException("微信会话未激活");
        }
        if (!outboundGateway.isAvailable(target)) {
            throw new IllegalStateException("微信发送通道不可用");
        }
    }
}
