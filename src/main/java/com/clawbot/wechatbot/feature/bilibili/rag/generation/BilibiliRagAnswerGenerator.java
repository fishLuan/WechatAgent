package com.clawbot.wechatbot.feature.bilibili.rag.generation;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagContext;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.service.ChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class BilibiliRagAnswerGenerator {
    private static final long WARNING_INTERVAL_MILLIS = 30_000L;
    private final ObjectProvider<ChatService> chatService;
    private final AtomicLong lastFailureWarningAt = new AtomicLong();

    public BilibiliRagAnswerGenerator(ObjectProvider<ChatService> chatService) {
        this.chatService = chatService;
    }

    public String generate(BilibiliRagContext context) {
        ChatService chat = chatService.getIfAvailable();
        if (chat == null || !chat.isConfigured()) {
            return fallback(context);
        }
        try {
            return chat.chat(prompt(context), "").trim();
        } catch (Exception error) {
            logFallback(error);
            return fallback(context);
        }
    }

    private void logFallback(Exception error) {
        long now = System.currentTimeMillis();
        long previous = lastFailureWarningAt.get();
        if (now - previous < WARNING_INTERVAL_MILLIS
            || !lastFailureWarningAt.compareAndSet(previous, now)) {
            return;
        }
        String reason = error.getMessage();
        System.err.println("[BILIBILI-RAG] 生成模型暂时不可用，已使用本地检索结果："
            + (reason == null || reason.isBlank()
                ? error.getClass().getSimpleName()
                : reason));
    }

    private String prompt(BilibiliRagContext context) {
        return """
            你是微信机器人里的 B 站动漫/剧集/电影推荐助手。
            只能依据下面给出的项目内数据回答，不要编造不存在的作品、评分、链接或订阅状态。
            必须遵守业务规则：动漫和连载剧集可以建议“订阅追更”；电影只能建议“想看、看过、不喜欢”，不能建议追更订阅。
            避免推荐用户已看过或不喜欢的作品。
            输出规范：
            1. 最多推荐 3 部作品。
            2. 每部最多 2 行：第一行标题、评分、题材；第二行简短理由或链接。
            3. 不要使用 Markdown 表格、长标题、分级标题、引用块或长篇解释。
            4. 不要使用 emoji 编号。
            5. 结尾只给一句操作提示，必须带编号，例如“订阅1”“看过1”“想看1”。
            6. 电影只能提示“想看1/看过1/不喜欢1”，不能提示订阅或追更。
            7. 动漫和剧集只有未完结时才提示“订阅1”追更；已完结作品只能提示“看过1”标记。

            用户问题：
            %s

            用户上下文：
            %s

            检索到的作品：
            %s
            """.formatted(
            context.request().question(),
            blankToDefault(context.userContext(), "暂无用户偏好、历史或订阅上下文。"),
            documents(context));
    }

    private String documents(BilibiliRagContext context) {
        if (context.empty()) return "暂无匹配作品。";
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (BilibiliRagDocument doc : context.documents().stream().limit(3).toList()) {
            out.append(index++).append(". ")
                .append(doc.title()).append("，类型：").append(doc.contentType());
            if (doc.rating() != null) {
                out.append("，评分：")
                    .append(String.format(Locale.ROOT, "%.1f", doc.rating()));
            }
            if (!doc.genres().isEmpty()) {
                out.append("，题材：").append(String.join("、", doc.genres()));
            }
            if (doc.latestEpisodeNumber() != null) {
                out.append("，最新集数：").append(doc.latestEpisodeNumber());
            }
            if (doc.finished()) out.append("，已完结");
            if (hasText(doc.description())) out.append("\n简介：").append(limit(doc.description(), 80));
            if (hasText(doc.pageUrl())) out.append("\n链接：").append(doc.pageUrl());
            out.append("\n\n");
        }
        return out.toString().trim();
    }

    private String fallback(BilibiliRagContext context) {
        if (context.empty()) {
            return "暂时没有在本地 B 站作品库里找到足够相关的内容，可以先换个作品名或题材再问。";
        }
        StringBuilder out = new StringBuilder("按本地作品库推荐：\n\n");
        int index = 1;
        for (BilibiliRagDocument doc : context.documents().stream().limit(3).toList()) {
            out.append(index++).append(". ").append(doc.title());
            if (doc.rating() != null) {
                out.append(" ⭐").append(String.format(Locale.ROOT, "%.1f", doc.rating()));
            }
            if (!doc.genres().isEmpty()) out.append(" · ").append(String.join("、", doc.genres()));
            out.append('\n');
            if (hasText(doc.pageUrl())) out.append(doc.pageUrl()).append('\n');
        }
        out.append("\n").append(actionHint(context));
        return out.toString().trim();
    }

    private String actionHint(BilibiliRagContext context) {
        ContentType type = context.request().preferredContentType();
        boolean movieOnly = type == ContentType.MOVIE
            || context.documents().stream().allMatch(doc -> doc.contentType() == ContentType.MOVIE);
        return movieOnly
            ? "电影可回复“想看1”“看过1”或“不喜欢1”。"
            : episodeTrackableHint(context);
    }

    private String episodeTrackableHint(BilibiliRagContext context) {
        boolean hasOngoing = context.documents().stream()
            .anyMatch(doc -> doc.contentType().isEpisodeTrackable() && !doc.finished());
        if (hasOngoing) {
            return "未完结动漫/剧集可回复“订阅1”追更，已看过可回复“看过1”。";
        }
        return "这些作品已完结，可回复“看过1”标记。";
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToDefault(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }
}
