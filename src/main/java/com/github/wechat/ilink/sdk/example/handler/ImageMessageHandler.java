package com.github.wechat.ilink.sdk.example.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.example.base.MessageHandler;
import com.github.wechat.ilink.sdk.example.service.VisionService;

/**
 * 图片消息处理器 —— 用户发图片时，调用百炼视觉模型生成描述
 */
public class ImageMessageHandler implements MessageHandler {

    private final VisionService visionService;

    public ImageMessageHandler(VisionService visionService) {
        this.visionService = visionService;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();

        System.out.println("[RECV] <" + from + "> [图片消息]");

        // Key 未配置 → 提示
        if (!visionService.isConfigured()) {
            safeSendText(client, from, "（我暂时无法识别图片，请先配置 DASHSCOPE_API_KEY 后再试）");
            return;
        }

        // 下载图片字节（取第一张图片）
        byte[] imageBytes = downloadFirstImage(client, msg);
        if (imageBytes == null || imageBytes.length == 0) {
            safeSendText(client, from, "图片下载失败了，请换一张试试～");
            return;
        }

        // 从消息中提取文本作为问题（用户可能在发图的同时写了文字）
        String question = extractUserTextQuestion(msg);

        // 调用百炼看图 —— 默认 prompt 让模型用生动口语化的语气描述，带点表情
        try {
            System.out.println("[INFO] 正在调用百炼图片理解模型...");
            String description = visionService.understandImage(imageBytes,
                question.isEmpty()
                    ? "用生动口语化的语气描述这张图片，2-3 句话就行，不要分点、不要写详细分析，最后可以带一个合适的 emoji 表情"
                    : question);
            safeSendText(client, from, description);
            System.out.println("[SEND] " + description.replace("\n", " | "));
        } catch (Exception e) {
            System.err.println("[ERROR] 图片理解失败: " + e.getMessage());
            safeSendText(client, from, "抱歉，图片识别出问题了："
                + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    @Override
    public int priority() { return 10; } // 图片消息最优先

    // ============================================================
    // 工具方法
    // ============================================================

    private byte[] downloadFirstImage(ILinkClient client, WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                try {
                    return client.downloadImageFromMessageItem(item);
                } catch (Exception e) {
                    System.err.println("[ERROR] 图片下载失败: " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private String extractUserTextQuestion(WeixinMessage msg) {
        if (msg.getItem_list() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            }
        }
        // 把 [图片] [语音] 等占位符去掉
        return sb.toString()
            .replace("[图片]", "").replace("[语音]", "")
            .replace("[文件]", "").replace("[视频]", "")
            .trim();
    }

    private void safeSendText(ILinkClient client, String to, String text) {
        try {
            long typingMillis = Math.min(2500, 500L + text.length() * 20L);
            client.sendTextWithTyping(to, text, typingMillis);
        } catch (Exception e) {
            System.err.println("[ERROR] 发送失败: " + e.getMessage());
        }
    }
}