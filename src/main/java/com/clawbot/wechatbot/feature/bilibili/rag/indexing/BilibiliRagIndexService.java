package com.clawbot.wechatbot.feature.bilibili.rag.indexing;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.rag.embedding.EmbeddingService;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 负责把 bilibili_content 同步成可向量检索的 RAG 文档。 */
@Service
public class BilibiliRagIndexService {
    private final BilibiliProperties properties;
    private final EmbeddingService embeddingService;
    private final BilibiliRagDocumentTextBuilder textBuilder;
    private final BilibiliContentRepository contents;
    private final BilibiliRagVectorRepository vectors;

    public BilibiliRagIndexService(
        BilibiliProperties properties,
        EmbeddingService embeddingService,
        BilibiliRagDocumentTextBuilder textBuilder,
        BilibiliContentRepository contents,
        BilibiliRagVectorRepository vectors
    ) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.textBuilder = textBuilder;
        this.contents = contents;
        this.vectors = vectors;
    }

    public IndexStats rebuildAll() {
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        for (BilibiliContent content : contents.findAll()) {
            try {
                if (index(content)) indexed++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                System.err.println("[BILIBILI-RAG] 索引失败："
                    + content.getContentType() + ":" + content.getContentId()
                    + "，原因：" + safeMessage(e));
            }
        }
        return new IndexStats(indexed, skipped, failed);
    }

    public boolean index(BilibiliContent content) throws Exception {
        if (!properties.getRag().getVector().isEnabled()
            || !embeddingService.isConfigured()
            || content == null
            || content.getContentType() == null
            || content.getContentId() == null
            || content.getContentId().isBlank()) {
            return false;
        }
        String text = textBuilder.build(content);
        if (text.isBlank()) return false;
        String hash = hash(text);
        BilibiliRagVectorDocument document = vectors
            .findByContentTypeAndContentIdAndEmbeddingModelAndEmbeddingDimension(
                content.getContentType(),
                content.getContentId(),
                embeddingService.model(),
                embeddingService.dimension())
            .orElseGet(BilibiliRagVectorDocument::new);
        if (hash.equals(document.getContentHash())) return false;

        List<List<Double>> embeddings = embeddingService.embedDocuments(List.of(text));
        if (embeddings.size() != 1) {
            throw new IllegalStateException(
                "Embedding 返回数量异常，期望 1，实际 " + embeddings.size());
        }
        List<Double> embedding = embeddings.getFirst();
        validateEmbedding(embedding);
        document.setContentType(content.getContentType());
        document.setContentId(content.getContentId());
        document.setSeasonId(content.getSeasonId());
        document.setTitle(content.getTitle());
        document.setText(text);
        document.setEmbedding(embedding);
        document.setEmbeddingModel(embeddingService.model());
        document.setEmbeddingDimension(embeddingService.dimension());
        document.setContentHash(hash);
        document.setSourceUpdatedAt(content.getUpdatedAt());
        document.setIndexedAt(Instant.now());
        vectors.save(document);
        return true;
    }

    private String hash(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void validateEmbedding(List<Double> embedding) {
        if (embedding.size() != embeddingService.dimension()) {
            throw new IllegalStateException(
                "Embedding 维度异常，期望 " + embeddingService.dimension()
                    + "，实际 " + embedding.size());
        }
        if (embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalStateException("Embedding 包含非有限数值");
        }
        boolean allZero = embedding.stream().allMatch(value -> value == 0.0d);
        if (allZero) throw new IllegalStateException("Embedding 不能为全零向量");
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }

    public record IndexStats(int indexed, int skipped, int failed) {
    }
}
