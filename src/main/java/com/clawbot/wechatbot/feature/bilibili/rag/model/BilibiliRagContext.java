package com.clawbot.wechatbot.feature.bilibili.rag.model;

import java.util.List;

public record BilibiliRagContext(
    BilibiliRagRequest request,
    List<BilibiliRagDocument> documents,
    String userContext
) {
    public boolean empty() {
        return documents == null || documents.isEmpty();
    }
}
