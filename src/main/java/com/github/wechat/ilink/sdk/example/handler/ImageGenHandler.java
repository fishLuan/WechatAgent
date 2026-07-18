package com.github.wechat.ilink.sdk.example.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.example.base.MessageHandler;
import com.github.wechat.ilink.sdk.example.service.ImageGenService;

/**
 * 文生图指令处理器 —— 用户发"画图一只猫"、"来一张小猫图片"等，触发百炼文生图
 */
public class ImageGenHandler implements MessageHandler {

    private final ImageGenService imageGenService;

    public ImageGenHandler(ImageGenService imageGenService) {
        this.imageGenService = imageGenService;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        String text = extractText(msg);
        if (text == null || text.trim().isEmpty()) return false;
        return extractImagePrompt(text) != null;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        String text = extractText(msg);
        String prompt = extractImagePrompt(text);

        System.out.println("[RECV] <" + from + "> [画图指令] " + prompt);

        // Key 未配置 → 提示
        if (!imageGenService.isConfigured()) {
            safeSendText(client, from, "（我暂时无法生成图片，请先配置 DASHSCOPE_API_KEY 后再试）");
            return;
        }

        // 先发一条"正在画图"的提示
        String pendingMsg = "好的，正在为你画图：" + prompt + "\n（生成图片需要一点时间，请耐心等待～）";
        safeSendText(client, from, pendingMsg);

        // 调用百炼生成图片
        try {
            System.out.println("[INFO] 正在调用百炼文生图模型...");
            byte[] imageBytes = imageGenService.generateImage(prompt);

            // 发送图片给用户
            String fileName = "ai-generated-" + System.currentTimeMillis() + ".png";
            client.sendImage(from, imageBytes, fileName, null);
            System.out.println("[SEND] [图片] " + fileName + " (" + imageBytes.length + " bytes)");
        } catch (Exception e) {
            System.err.println("[ERROR] 图片生成失败: " + e.getMessage());
            safeSendText(client, from, "抱歉，画图出问题了："
                + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    @Override
    public int priority() { return 50; } // 比纯文本早，比图片晚

    // ============================================================
    // 工具方法
    // ============================================================

    private String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            }
        }
        return sb.toString();
    }

    /**
     * 检测"画图"等关键词，返回去掉关键词后的描述；不是画图指令返回 null
     */
    private String extractImagePrompt(String userText) {
        if (userText == null) return null;
        String t = userText.trim();

        // 注意：长的前缀先判断，避免被短的截断
        String[] prefixes = {
            // 明确的画图/生成图
            "画图：", "画图:", "画图 ", "画图",
            "生成图：", "生成图:", "生成图 ", "生成图",

            // 一张类
            "画一张：", "画一张:", "画一张 ", "画一张",
            "生成一张：", "生成一张:", "生成一张 ", "生成一张",
            "来一张：", "来一张:", "来一张 ", "来一张",
            "给一张：", "给一张:", "给一张 ", "给一张",
            "来张：", "来张:", "来张 ", "来张",

            // 请求类
            "帮我画一张：", "帮我画一张:", "帮我画一张 ", "帮我画一张",
            "给我画一张：", "给我画一张:", "给我画一张 ", "给我画一张",
            "帮我画个：", "帮我画个:", "帮我画个 ", "帮我画个",
            "给我画个：", "给我画个:", "给我画个 ", "给我画个",
            "帮我画：", "帮我画:", "帮我画 ", "帮我画",
            "给我画：", "给我画:", "给我画 ", "给我画",
            "帮我生成一张：", "帮我生成一张 ", "帮我生成一张",
            "给我生成一张：", "给我生成一张 ", "给我生成一张",

            // 简短类
            "画个：", "画个:", "画个 ", "画个",
            "画 "
        };

        for (String prefix : prefixes) {
            if (t.startsWith(prefix)) {
                String prompt = t.substring(prefix.length()).trim();
                if (prompt.startsWith("：") || prompt.startsWith(":")) {
                    prompt = prompt.substring(1).trim();
                }
                // "画一张图片"这种无效描述（没有实际内容）过滤掉
                String[] redundant = {"一张图片", "一张图", "张图片", "张图", "图片"};
                for (String r : redundant) {
                    if (prompt.equals(r)) return null;
                }
                return prompt.isEmpty() ? null : prompt;
            }
        }
        return null;
    }

    private void safeSendText(ILinkClient client, String to, String text) {
        try {
            long typingMillis = Math.min(2000, 500L + text.length() * 15L);
            client.sendTextWithTyping(to, text, typingMillis);
        } catch (Exception e) {
            System.err.println("[ERROR] 发送失败: " + e.getMessage());
        }
    }
}