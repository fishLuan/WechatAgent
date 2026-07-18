package com.github.wechat.ilink.sdk.example.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.example.base.MessageHandler;
import com.github.wechat.ilink.sdk.example.service.ChatService;
import com.github.wechat.ilink.sdk.example.util.JsonUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * 文本消息处理器 —— 处理用户发来的普通文本，调用 DeepSeek 对话
 *
 * 注意：这是"兜底" Handler，优先级最低（priority 最大）。
 * 其他 Handler（ImageMessageHandler、ImageGenHandler）先判断，
 * 如果都不处理，最后才进入这里。
 */
public class TextMessageHandler implements MessageHandler {

    private final ChatService chatService;
    private final StringBuilder conversationHistory = new StringBuilder();
    private final Set<Long> processedMsgIds = new HashSet<>();

    public TextMessageHandler(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        // 有图片的留给 ImageMessageHandler
        if (hasImage(msg)) return false;
        // 空消息不处理
        String text = extractText(msg);
        return text != null && !text.trim().isEmpty();
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        String userText = extractText(msg);

        // 去重
        if (msg.getMessage_id() != null) {
            if (!processedMsgIds.add(msg.getMessage_id())) return;
        }

        // 特殊命令
        if (isCommand(userText)) {
            handleCommand(client, from, userText);
            return;
        }

        // 未配置 Key → echo
        if (!chatService.isConfigured()) {
            safeSendText(client, from, "（Echo模式）你说: " + userText
                + "\n提示：配置环境变量 DEEPSEEK_API_KEY 开启智能对话");
            return;
        }

        // DeepSeek 对话
        try {
            String reply = chatService.chat(userText, conversationHistory.toString());
            safeSendText(client, from, reply);
            appendHistory(userText, reply);
            System.out.println("[RECV] <" + from + "> " + userText + " → [SEND] " + reply.replace("\n", " | "));
        } catch (Exception e) {
            System.err.println("[ERROR] DeepSeek: " + e.getMessage());
            safeSendText(client, from, "抱歉，大脑暂时短路了：" + e.getMessage());
        }
    }

    @Override
    public int priority() { return 100; } // 兜底，最后才到

    // ============================================================
    // 工具方法
    // ============================================================

    private boolean isCommand(String text) {
        String t = text.trim().toLowerCase();
        return t.equals("help") || t.equals("帮助") || t.equals("?")
            || t.equals("clear") || t.equals("清空") || t.equals("重置");
    }

    private void handleCommand(ILinkClient client, String from, String text) {
        String t = text.trim().toLowerCase();
        if (t.equals("help") || t.equals("帮助") || t.equals("?")) {
            safeSendText(client, from,
                "我可以做的事情："
                + "\n1. 文本对话（接入 DeepSeek 大模型）"
                + "\n2. 看图识别（发送图片即可，同时接入阿里云百炼视觉模型）"
                + "\n3. 文生图（说「画图 一只在月球上的猫」即可生成图片）"
                + "\n（发送 'clear' 可清空对话记忆）");
            return;
        }
        if (t.equals("clear") || t.equals("清空") || t.equals("重置")) {
            conversationHistory.setLength(0);
            safeSendText(client, from, "对话记忆已清空，我们重新开始聊天吧！");
            return;
        }
    }

    private boolean hasImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    private String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            } else if (item.getImage_item() != null) {
                sb.append("[图片]");
            } else if (item.getVoice_item() != null) {
                sb.append("[语音]");
            } else if (item.getFile_item() != null) {
                sb.append("[文件]");
            } else if (item.getVideo_item() != null) {
                sb.append("[视频]");
            }
        }
        return sb.toString();
    }

    private void safeSendText(ILinkClient client, String to, String text) {
        try {
            long typingMillis = Math.min(2000, 300L + text.length() * 20L);
            client.sendTextWithTyping(to, text, typingMillis);
        } catch (Exception e) {
            System.err.println("[ERROR] 发送失败: " + e.getMessage());
        }
    }

    private void appendHistory(String userText, String assistantReply) {
        if (conversationHistory.length() > 6000) conversationHistory.setLength(0);
        if (conversationHistory.length() > 0) conversationHistory.append(",");
        conversationHistory.append("{\"role\":\"user\",\"content\":")
            .append(JsonUtils.escape(userText)).append("},");
        conversationHistory.append("{\"role\":\"assistant\",\"content\":")
            .append(JsonUtils.escape(assistantReply)).append("}");
    }
}