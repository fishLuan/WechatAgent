package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/** B站消息入口共用的微信文本提取器。 */
final class WeChatMessageTextExtractor {
    private WeChatMessageTextExtractor() {
    }

    static String extract(WeixinMessage message) {
        if (message == null || message.getItem_list() == null) return "";
        StringBuilder result = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            if (item.getType() == 1 && item.getText_item() != null) {
                result.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem voice = item.getVoice_item();
                if (voice.getText() != null) result.append(voice.getText());
            }
        }
        return result.toString();
    }
}
