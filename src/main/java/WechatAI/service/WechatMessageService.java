package WechatAI.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

public interface WechatMessageService {

    void handleAndReply(WeixinMessage message);
}
