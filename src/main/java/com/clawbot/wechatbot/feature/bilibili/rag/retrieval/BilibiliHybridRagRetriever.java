package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.embedding.EmbeddingService;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 向量结果优先，关键词结果补充的混合检索器。 */
@Component
public class BilibiliHybridRagRetriever {
    private final BilibiliProperties properties;
    private final EmbeddingService embeddingService;
    private final BilibiliRagVectorRepository vectors;
    private final BilibiliContentRepository contents;
    private final BilibiliRagRetriever keywordRetriever;

    public BilibiliHybridRagRetriever(
        BilibiliProperties properties,
        EmbeddingService embeddingService,
        BilibiliRagVectorRepository vectors,
        BilibiliContentRepository contents,
        BilibiliRagRetriever keywordRetriever
    ) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.vectors = vectors;
        this.contents = contents;
        this.keywordRetriever = keywordRetriever;
    }

    public List<BilibiliRagDocument> retrieve(
        String question, ContentType preferredType, String referenceTitle, int limit
    ) {
        Map<String, BilibiliRagDocument> merged = new LinkedHashMap<>();
        if (properties.getRag().getVector().isEnabled() && embeddingService.isConfigured()) {
            for (BilibiliRagDocument document : retrieveByVector(question, preferredType, limit)) {
                merged.putIfAbsent(key(document), document);
            }
        }
        for (BilibiliRagDocument document : keywordRetriever.retrieve(
            question, preferredType, referenceTitle, limit)) {
            merged.putIfAbsent(key(document), document);
        }
        return merged.values().stream().limit(Math.max(1, limit)).toList();
    }

    private List<BilibiliRagDocument> retrieveByVector(
        String question, ContentType preferredType, int limit
    ) {
        try {
            List<Double> queryEmbedding = embeddingService.embedQuery(question);
            if (queryEmbedding.isEmpty()) return List.of();
            List<BilibiliRagVectorDocument> candidates = preferredType == null
                ? vectors.findByEmbeddingModelAndEmbeddingDimension(
                    embeddingService.model(), embeddingService.dimension())
                : vectors.findByContentTypeAndEmbeddingModelAndEmbeddingDimension(
                    preferredType, embeddingService.model(), embeddingService.dimension());
            return candidates.stream()
                .filter(item -> item.getEmbedding().size() == queryEmbedding.size())
                .map(item -> new ScoredVector(item, cosine(queryEmbedding, item.getEmbedding())))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredVector::score).reversed())
                .limit(Math.max(1, limit))
                .map(ScoredVector::document)
                .map(this::loadContent)
                .flatMap(List::stream)
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<BilibiliRagDocument> loadContent(BilibiliRagVectorDocument vector) {
        return contents.findByContentTypeAndContentId(
                vector.getContentType(), vector.getContentId())
            .map(BilibiliRagDocument::from)
            .map(List::of)
            .orElseGet(List::of);
    }

    private double cosine(List<Double> left, List<Double> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String key(BilibiliRagDocument document) {
        return document.contentType() + ":" + document.contentId();
    }

    private record ScoredVector(BilibiliRagVectorDocument document, double score) {
    }
}
