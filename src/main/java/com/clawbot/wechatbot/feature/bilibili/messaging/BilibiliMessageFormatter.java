package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

/** 将 B 站结构化内容整理为微信文本。 */
public class BilibiliMessageFormatter {

    public String formatResolvedContent(BilibiliContent content) {
        StringBuilder reply = new StringBuilder();
        reply.append("已识别 B 站")
            .append(displayType(content.getContentType()))
            .append("：")
            .append(content.getTitle());

        if (content.getRating() != null) {
            reply.append("\n评分：").append(trimNumber(content.getRating()));
        }
        if (!content.getGenres().isEmpty()) {
            reply.append("\n类型：").append(String.join(" / ", content.getGenres()));
        }
        if (content.getLatestEpisodeNumber() != null
                || hasText(content.getLatestEpisodeTitle())) {
            reply.append("\n最新：");
            if (content.getLatestEpisodeNumber() != null) {
                reply.append("第").append(content.getLatestEpisodeNumber()).append("集");
            }
            if (hasText(content.getLatestEpisodeTitle())) {
                reply.append(" ").append(content.getLatestEpisodeTitle());
            }
        }
        if (hasText(content.getDescription())) {
            reply.append("\n简介：").append(limit(content.getDescription(), 120));
        }
        if (hasText(content.getPageUrl())) {
            reply.append("\n链接：").append(content.getPageUrl());
        }
        return reply.toString();
    }

    public String formatResolveFailure(String message) {
        String reason = hasText(message) ? message : "未知错误";
        return "这个 B 站链接暂时解析失败：" + reason
            + "\n可以稍后再试，或换一个番剧/电影/BV 链接。";
    }

    private String displayType(ContentType type) {
        if (type == ContentType.MOVIE) return "电影";
        if (type == ContentType.BANGUMI) return "番剧";
        if (type == ContentType.SERIES) return "视频/剧集";
        if (type == ContentType.UPLOADER) return "UP 主";
        return "内容";
    }

    private String trimNumber(Double value) {
        if (value == null) return "";
        return value % 1 == 0
            ? Long.toString(value.longValue())
            : Double.toString(value);
    }

    private String limit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars).trim() + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
