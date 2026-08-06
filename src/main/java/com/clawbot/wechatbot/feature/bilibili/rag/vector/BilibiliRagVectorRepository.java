package com.clawbot.wechatbot.feature.bilibili.rag.vector;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BilibiliRagVectorRepository
    extends MongoRepository<BilibiliRagVectorDocument, String> {

    Optional<BilibiliRagVectorDocument> findByContentTypeAndContentIdAndEmbeddingModelAndEmbeddingDimension(
        ContentType contentType, String contentId, String embeddingModel, int embeddingDimension);

    List<BilibiliRagVectorDocument> findByContentTypeAndEmbeddingModelAndEmbeddingDimension(
        ContentType contentType, String embeddingModel, int embeddingDimension);

    List<BilibiliRagVectorDocument> findByEmbeddingModelAndEmbeddingDimension(
        String embeddingModel, int embeddingDimension);
}
