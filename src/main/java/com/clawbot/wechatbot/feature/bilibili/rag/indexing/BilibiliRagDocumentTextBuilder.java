package com.clawbot.wechatbot.feature.bilibili.rag.indexing;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import org.springframework.stereotype.Component;

/** 将作品快照压成适合向量化的稳定文本。 */
@Component
public class BilibiliRagDocumentTextBuilder {
    public String build(BilibiliContent content) {
        StringBuilder out = new StringBuilder();
        append(out, "标题", content.getTitle());
        append(out, "类型", content.getContentType() == null ? "" : content.getContentType().name());
        if (!content.getGenres().isEmpty()) {
            append(out, "题材", String.join("、", content.getGenres()));
        }
        append(out, "简介", content.getDescription());
        append(out, "最新集", content.getLatestEpisodeTitle());
        if (content.getLatestEpisodeNumber() != null) {
            append(out, "集数", String.valueOf(content.getLatestEpisodeNumber()));
        }
        if (content.getRating() != null) {
            append(out, "评分", String.valueOf(content.getRating()));
        }
        append(out, "状态", content.isFinished() ? "已完结" : "连载中");
        return out.toString().trim();
    }

    private void append(StringBuilder out, String label, String value) {
        if (value == null || value.isBlank()) return;
        out.append(label).append("：").append(value.trim()).append('\n');
    }
}
