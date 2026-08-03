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

        List<Double> embedding = embeddingService.embedDocuments(List.of(text)).getFirst();
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

    public record IndexStats(int indexed, int skipped, int failed) {
    }
}
