package WechatAI.service;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

public class MessageTextExtractor {

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
