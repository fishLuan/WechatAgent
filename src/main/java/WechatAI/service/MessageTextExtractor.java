package WechatAI.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

public interface MessageTextExtractor {

    String extract(WeixinMessage message);
}
