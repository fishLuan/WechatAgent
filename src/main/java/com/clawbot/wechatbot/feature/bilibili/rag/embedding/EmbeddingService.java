package com.clawbot.wechatbot.feature.bilibili.rag.embedding;

import java.util.List;

/** 文本向量化能力，供 RAG 索引和查询复用。 */
public interface EmbeddingService {
    boolean isConfigured();

    String model();

    int dimension();

    List<List<Double>> embedDocuments(List<String> texts) throws Exception;

    List<Double> embedQuery(String text) throws Exception;
}
