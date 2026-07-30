package com.clawbot.wechatbot.feature.bilibili.messaging;

/**
 * B站模块唯一的微信出站端口。
 *
 * <p>业务代码不持有微信 SDK 客户端，只通过用户 ID 发送消息。</p>
 */
public interface WeChatOutboundGateway {
    void sendText(String wechatUserId, String content);

    boolean isAvailable(String wechatUserId);
}
