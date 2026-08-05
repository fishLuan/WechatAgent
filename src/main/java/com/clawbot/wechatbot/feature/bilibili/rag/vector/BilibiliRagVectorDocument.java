package com.clawbot.wechatbot.feature.bilibili.rag.vector;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Mongo 中保存的 RAG 向量文档。 */
@Document(collection = "bilibili_rag_vector_document")
@CompoundIndex(
    name = "uk_bilibili_vector_content_model",
    def = "{'contentType': 1, 'contentId': 1, 'embeddingModel': 1, 'embeddingDimension': 1}",
    unique = true
)
public class BilibiliRagVectorDocument {
    @Id
    private String id;
    private ContentType contentType;
    private String contentId;
    private String seasonId;
    private String title;
    private String text;
    private List<Double> embedding = new ArrayList<>();
    private String embeddingModel;
    private int embeddingDimension;
    private String contentHash;
    private Instant sourceUpdatedAt;
    private Instant indexedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public ContentType getContentType() { return contentType; }
    public void setContentType(ContentType contentType) { this.contentType = contentType; }
    public String getContentId() { return contentId; }
    public void setContentId(String contentId) { this.contentId = contentId; }
    public String getSeasonId() { return seasonId; }
    public void setSeasonId(String seasonId) { this.seasonId = seasonId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<Double> getEmbedding() {
        if (embedding == null) embedding = new ArrayList<>();
        return embedding;
    }
    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding == null ? new ArrayList<>() : new ArrayList<>(embedding);
    }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(Instant sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }
    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant indexedAt) { this.indexedAt = indexedAt; }
}
