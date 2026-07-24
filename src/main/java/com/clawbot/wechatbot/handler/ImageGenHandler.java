package com.clawbot.wechatbot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.service.ImageGenService;

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
     * 检测"画图/给我画张..."等关键词，返回去掉关键词后的描述；不是画图指令返回 null
     */
    private String extractImagePrompt(String userText) {
        if (userText == null) return null;
        String t = userText.trim();
        String lower = t.toLowerCase();

        // ---- 第 1 步：长前缀精确匹配（优先级最高）----
        // 按长度从长到短排列，避免先被短的截断
        String[] prefixes = {
            // 请求 + 动作（带"帮我/给我"）
            "帮我画一张", "给我画一张", "帮我画个", "给我画个",
            "帮我画张", "给我画张", "帮我画", "给我画",
            "帮我画一张：", "帮我画一张:", "给我画一张：", "给我画一张:",
            "帮我画个：", "帮我画个:", "给我画个：", "给我画个:",
            "帮我画张：", "帮我画张:", "给我画张：", "给我画张:",
            "帮我生成一张图片", "给我生成一张图片",
            "帮我生成一张", "给我生成一张",
            "帮我生成图", "给我生成图",

            // 直接动作（不带请求词）
            "画图：", "画图:", "画图 ", "画图",
            "画一张：", "画一张:", "画一张 ", "画一张",
            "画张：", "画张:", "画张 ", "画张",
            "画个：", "画个:", "画个 ", "画个",
            "生成一张：", "生成一张:", "生成一张 ", "生成一张",
            "生成一张图片", "生成图：", "生成图:", "生成图 ", "生成图",
            "来一张：", "来一张:", "来一张 ", "来一张",
            "来张：", "来张:", "来张 ", "来张",
            "给一张：", "给一张:", "给一张 ", "给一张",
            "画 ",
        };
        for (String prefix : prefixes) {
            if (t.startsWith(prefix)) {
                return extractAfterPrefix(t, prefix);
            }
        }

        // ---- 第 2 步：包含关键词（如"你给我画张..."、"给我画张..."）----
        // 匹配第一个画图关键词，然后取关键词后面的全部内容作为 prompt
        // 关键词按长度从长到短
        String[] inlineKeywords = {
            "帮我画一张", "给我画一张",
            "帮我画张", "给我画张", "帮我画个", "给我画个",
            "帮我画", "给我画",
            "帮我生成一张图片", "给我生成一张图片",
            "帮我生成一张", "给我生成一张",
            "画一张", "画张", "画个",
            "生成一张图片", "生成一张", "生成图",
            "来一张", "来张", "给一张",
            "画图", "画图 ",
        };

        for (String kw : inlineKeywords) {
            int idx = t.indexOf(kw);
            if (idx >= 0) {
                String after = t.substring(idx + kw.length()).trim();
                // 去掉开头可能残留的冒号/空格/图片 这类词
                String prompt = stripColons(after);
                prompt = stripRedundantImageWords(prompt);
                if (prompt.isEmpty()) return null;
                return prompt;
            }
        }

        // ---- 第 3 步：只做 lower 不区分大小写的兜底 ----
        // 如果文本里明确包含"画"字 +（"张/个/图"），但没匹配到上面的格式，也作为画图
        // （这里比较保守，避免误匹配"画了一幅画"这种文本）
        return null;
    }

    private String extractAfterPrefix(String t, String prefix) {
        String prompt = t.substring(prefix.length()).trim();
        prompt = stripColons(prompt);
        prompt = stripRedundantImageWords(prompt);
        return prompt.isEmpty() ? null : prompt;
    }

    private String stripColons(String s) {
        while (!s.isEmpty() && (s.startsWith("：") || s.startsWith(":") || s.startsWith(" ") || s.startsWith("　"))) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private String stripRedundantImageWords(String s) {
        if (s.isEmpty()) return s;
        String[] redundant = {"一张图片", "一张图", "张图片", "张图", "一张", "图片"};
        for (String r : redundant) {
            if (s.equalsIgnoreCase(r)) return "";
        }
        // 去掉末尾的 "图片" / "的图片"
        String trimmed = s;
        if (trimmed.endsWith("的图片")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        if (trimmed.endsWith("图片")) trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
        return trimmed;
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