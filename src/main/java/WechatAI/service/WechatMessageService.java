package WechatAI.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/**
 * 微信消息业务处理入口，负责按消息类型路由到对应能力。
 */
public interface WechatMessageService {

    void handleAndReply(WeixinMessage message);
}
