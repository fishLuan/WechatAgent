package com.clawbot.wechatbot.messaging;

import com.clawbot.wechatbot.base.MessageSender;
import com.clawbot.wechatbot.notification.NotificationService;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.springframework.stereotype.Component;

/**
 * 统一的微信出站消息入口。发送客户端由用户与接收会话的绑定关系决定。
 */
@Component
public class WeChatMessageSender implements MessageSender {
    private static final int MAX_TEXT_CHARS = 1500;

    private final WeChatClientRegistry clientRegistry;
    private final NotificationService notifications;

    public WeChatMessageSender(
        WeChatClientRegistry clientRegistry,
        NotificationService notifications
    ) {
        this.clientRegistry = clientRegistry;
        this.notifications = notifications;
    }

    @Override
    public void sendText(String userId, String text) {
        if (text == null || text.isBlank()) return;
        ILinkClient client = clientRegistry.requireClient(userId);
        try {
            for (int start = 0; start < text.length();) {
                int end = safeChunkEnd(text, start);
                client.sendText(userId, text.substring(start, end));
                start = end;
            }
        } catch (Exception e) {
            throw sendFailure("文本", userId, e);
        }
    }

    private int safeChunkEnd(String text, int start) {
        int end = Math.min(start + MAX_TEXT_CHARS, text.length());
        if (end < text.length()
            && end > start
            && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end > start ? end : Math.min(start + 1, text.length());
    }

    @Override
    public void sendImage(String userId, byte[] imageBytes, String fileName) {
        ILinkClient client = clientRegistry.requireClient(userId);
        try {
            client.sendImage(userId, imageBytes, fileName, null);
        } catch (Exception e) {
            throw sendFailure("图片", userId, e);
        }
    }

    @Override
    public void sendFile(String userId, byte[] fileBytes, String fileName, String caption) {
        ILinkClient client = clientRegistry.requireClient(userId);
        try {
            client.sendFile(userId, fileBytes, fileName, caption);
        } catch (Exception e) {
            throw sendFailure("文件", userId, e);
        }
    }

    @Override
    public boolean isReady() {
        return clientRegistry.isReady();
    }

    @Override
    public boolean isReadyFor(String userId) {
        return clientRegistry.hasActiveUserBinding(userId);
    }

    private IllegalStateException sendFailure(
        String messageType,
        String userId,
        Exception error
    ) {
        String target = userId == null ? "<unknown>" : userId;
        System.err.println("[WECHAT-SEND] " + messageType + "消息发送失败，userId="
            + target + "：" + error.getMessage());
        notifications.notifyError("微信" + messageType + "消息发送/" + target, error);
        return new IllegalStateException("微信" + messageType + "消息发送失败", error);
    }
}
