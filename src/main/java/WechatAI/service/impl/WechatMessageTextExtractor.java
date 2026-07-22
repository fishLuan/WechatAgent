package WechatAI.service.impl;

import WechatAI.service.MessageTextExtractor;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 默认微信文本提取实现，拼接消息内所有文本片段。
 */
public class WechatMessageTextExtractor implements MessageTextExtractor {

    @Override
    public String extract(WeixinMessage message) {
        List<MessageItem> items = message.getItem_list();
        if (items == null || items.isEmpty()) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();
        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                textBuilder.append(item.getText_item().getText());
            }
        }
        return textBuilder.toString();
    }
}
