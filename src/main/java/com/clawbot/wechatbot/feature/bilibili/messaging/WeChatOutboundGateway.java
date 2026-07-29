package com.clawbot.wechatbot.feature.bilibili.messaging;

/** 定时任务和业务通知主动发送微信消息的统一出口。 */
public interface WeChatOutboundGateway {
    boolean isAvailable(String wechatUserId);

    void sendText(String wechatUserId, String content);
}
