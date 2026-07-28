package com.clawbot.wechatbot.scheduler;

import com.clawbot.wechatbot.notification.NotificationService;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.springframework.context.event.EventListener;

import java.util.concurrent.ConcurrentHashMap;

public class WeChatMessageSender {

    private final ConcurrentHashMap<String, ILinkClient> clients = new ConcurrentHashMap<>();
    private final NotificationService notifications;
    private volatile ILinkClient lastReadyClient;  // 缓存第一个就绪的，避免每次 findFirst

    public WeChatMessageSender(NotificationService notifications) {
        this.notifications = notifications;
    }

    @EventListener
    public void onClientReady(WeChatClientReadyEvent event) {
        if (event.client() == null) {
            System.err.println("[SCHEDULER] ⚠️ 收到 WeChatClientReadyEvent，但 client 为 null，忽略！botId=" + event.botId());
            return;
        }
        clients.put(event.botId(), event.client());
        lastReadyClient = event.client();
        System.out.println("[SCHEDULER] ✅ WeChatMessageSender 已接入 Bot：" + event.botId()
            + "，当前在线 Bot 数=" + clients.size());
    }

    public boolean isReady() {
        return lastReadyClient != null && !clients.isEmpty();
    }

    public void sendText(String toUserId, String text) {
        ILinkClient client = lastReadyClient != null
            ? lastReadyClient
            : clients.values().stream().findFirst().orElse(null);
        if (client == null) {
            String msg = "微信客户端尚未就绪，暂无法发送消息（WeChatMessageSender.clients 空），to=" + toUserId;
            System.err.println("[SCHEDULER] ❌ " + msg);
            notifications.notifyError("定时任务微信发送失败/无客户端", new IllegalStateException(msg));
            throw new IllegalStateException(msg);
        }
        long typingMillis = Math.min(2000, 300L + text.length() * 20L);
        try {
            System.out.printf("[SCHEDULER] 📤 发送微信：to=%s  len=%d  typingMs=%d%n", toUserId, text.length(), typingMillis);
            client.sendTextWithTyping(toUserId, text, typingMillis);
        } catch (Exception e) {
            System.err.printf("[SCHEDULER] ❌ 微信发送异常 to=%s  err=%s%n", toUserId, e.getMessage());
            e.printStackTrace();
            notifications.notifyError("定时任务微信发送失败", e);
            throw new RuntimeException("微信消息发送失败: " + e.getMessage(), e);
        }
    }
}