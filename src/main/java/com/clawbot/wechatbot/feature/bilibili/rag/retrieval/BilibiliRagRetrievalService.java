package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;

import java.util.List;

/** B 站 RAG 候选召回的统一入口。 */
public interface BilibiliRagRetrievalService {
    List<BilibiliRagDocument> retrieve(
        String question,
        ContentType preferredType,
        String referenceTitle,
        int limit);
}
