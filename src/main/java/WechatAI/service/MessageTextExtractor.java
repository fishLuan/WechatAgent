package WechatAI.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/**
 * 微信消息文本提取器，统一处理不同消息结构下的文本读取。
 */
public interface MessageTextExtractor {

    String extract(WeixinMessage message);
}
