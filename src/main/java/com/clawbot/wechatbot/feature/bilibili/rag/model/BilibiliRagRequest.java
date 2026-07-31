package com.clawbot.wechatbot.feature.bilibili.rag.model;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

public record BilibiliRagRequest(
    String wechatUserId,
    String question,
    ContentType preferredContentType,
    String referenceTitle
) {
}
