package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageSender;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class WeChatOutboundGatewayImpl implements WeChatOutboundGateway {
    private final MessageSender messageSender;

    public WeChatOutboundGatewayImpl(@Lazy MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @Override
    public boolean isAvailable(String wechatUserId) {
        return messageSender.isReady();
    }

    @Override
    public void sendText(String wechatUserId, String content) {
        if (wechatUserId == null || wechatUserId.isBlank()
            || content == null || content.isBlank()) {
            return;
        }
        messageSender.sendText(wechatUserId.trim(), content.trim());
    }
}