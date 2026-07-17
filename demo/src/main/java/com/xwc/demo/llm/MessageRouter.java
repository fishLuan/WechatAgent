package com.xwc.demo.llm;

import com.xwc.demo.llm.vision.ImageGenerator;
import com.xwc.demo.llm.vision.ImageReader;

import java.util.regex.Pattern;

public class MessageRouter {
    private final TextLlmService textLlm;
    private final ImageReader imageReader;
    private final ImageGenerator imageGen;
    private final LlmConfig config;

    // 画图意图的正则（匹配：画|生成.*图|draw|generate.*image）
    private static final Pattern DRAW_PATTERN = Pattern.compile(
            "(画|绘制|生成|draw).*?(图|图片|image)|帮我?画|来张?图|生成图片"
    );

    public MessageRouter(TextLlmService textLlm, ImageReader reader, ImageGenerator gen, LlmConfig config) {
        this.textLlm = textLlm;
        this.imageReader = reader;
        this.imageGen = gen;
        this.config = config;
    }

    /**
     * 路由决策
     */
    public ReplyResult route(String userId, String text, byte[] imageBytes) {
        try {
            // 1. 有图片 → 走视觉理解
            if (imageBytes != null && imageBytes.length > 0 && config.isVisionEnabled()) {
                String desc = imageReader.understand(imageBytes, text);
                return ReplyResult.text(desc);
            }

            // 2. 无图片但文本有画图意图 → 走文生图
            if (text != null && config.isImageGenEnabled() && isDrawIntent(text)) {
                String prompt = extractPrompt(text);
                byte[] img = imageGen.generate(prompt);
                return ReplyResult.image(img, "为你生成的图片~");
            }

            // 3. 默认 → 走文本对话
            if (text != null && config.isTextEnabled()) {
                String reply = textLlm.chat(userId, text);
                return ReplyResult.text(reply);
            }

        } catch (Exception e) {
            System.err.println("[MessageRouter] 处理失败: " + e.getMessage());
            return ReplyResult.text("抱歉，处理时出错了: " + e.getMessage());
        }

        return ReplyResult.text("我还没配置好，暂时无法回复");
    }

    private boolean isDrawIntent(String text) {
        return DRAW_PATTERN.matcher(text.toLowerCase()).find();
    }

    /**
     * 从用户输入中提取绘画关键词
     * "画一只猫" → "一只猫"
     * "帮我生成一张风景图" → "风景"
     */
    private String extractPrompt(String text) {
        // 去掉常见的指令前缀
        String s = text.replaceAll("(帮我?|请|可以)?(画|绘制|生成|draw)[一张|一个|一幅|个|张]?", "");
        s = s.replaceAll("[图图片image]", "").trim();
        return s.isEmpty() ? text : s;
    }

    // ==================== 回复结果封装 ====================

    public static class ReplyResult {
        enum Type { TEXT, IMAGE }
        Type type;
        String text;
        byte[] imageData;
        String caption;

        static ReplyResult text(String content) {
            ReplyResult r = new ReplyResult();
            r.type = Type.TEXT;
            r.text = content;
            return r;
        }

        static ReplyResult image(byte[] data, String caption) {
            ReplyResult r = new ReplyResult();
            r.type = Type.IMAGE;
            r.imageData = data;
            r.caption = caption;
            return r;
        }
    }
}
