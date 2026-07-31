package com.clawbot.wechatbot.feature.bilibili.rag;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

public interface BilibiliRagService {
    String answer(String wechatUserId, String question, ContentType preferredType);

    String answerSimilar(String wechatUserId, String title, ContentType preferredType);
}
