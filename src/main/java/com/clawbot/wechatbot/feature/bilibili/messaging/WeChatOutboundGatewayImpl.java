package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageSender;
import org.springframework.stereotype.Component;

@Component
public final class WeChatOutboundGatewayImpl implements WeChatOutboundGateway {
    private final MessageSender sender;

    public WeChatOutboundGatewayImpl(MessageSender sender) {
        this.sender = sender;
    }

    @Override
    public void sendText(String wechatUserId, String content) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("微信用户 ID 不能为空");
        }
        if (content == null || content.isBlank()) return;
        sender.sendText(wechatUserId.trim(), content.trim());
    }

    @Override
    public boolean isAvailable(String wechatUserId) {
        return wechatUserId != null
            && !wechatUserId.isBlank()
            && sender.isReady();
    }
}
