package WechatAI.support;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 微信消息附件提取工具，屏蔽 MessageItem 的类型判断细节。
 */
public final class MessageItemUtils {

    private MessageItemUtils() {
    }

    public static MessageItem firstImageItem(WeixinMessage message) {
        return firstMatching(message, ItemType.IMAGE);
    }

    public static MessageItem firstVoiceItem(WeixinMessage message) {
        return firstMatching(message, ItemType.VOICE);
    }

    public static MessageItem firstFileItem(WeixinMessage message) {
        return firstMatching(message, ItemType.FILE);
    }

    private static MessageItem firstMatching(WeixinMessage message, ItemType type) {
        List<MessageItem> items = message.getItem_list();
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (MessageItem item : items) {
            if (matches(item, type)) {
                return item;
            }
        }
        return null;
    }

    private static boolean matches(MessageItem item, ItemType type) {
        if (item == null) {
            return false;
        }
        if (type == ItemType.IMAGE) {
            return item.getImage_item() != null;
        }
        if (type == ItemType.VOICE) {
            return item.getVoice_item() != null;
        }
        return item.getFile_item() != null;
    }

    private enum ItemType {
        IMAGE,
        VOICE,
        FILE
    }
}
