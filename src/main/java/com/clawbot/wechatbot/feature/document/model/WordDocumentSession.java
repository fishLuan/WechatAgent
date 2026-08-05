package com.clawbot.wechatbot.feature.document.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** 用户最近上传并可编辑的 Word 文档会话。 */
@Document(collection = "word_document_session")
@CompoundIndex(name = "idx_word_document_user_active", def = "{'wechatUserId': 1, 'active': 1, 'updatedAt': -1}")
public class WordDocumentSession {
    @Id
    private String id;
    @Indexed
    private String wechatUserId;
    private String fileName;
    private byte[] content;
    private String extractedText;
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    public WordDocumentSession() {
    }

    public WordDocumentSession(String wechatUserId, String fileName, byte[] content) {
        this.wechatUserId = requireText(wechatUserId, "wechatUserId");
        this.fileName = requireText(fileName, "fileName");
        this.content = content == null ? new byte[0] : content.clone();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String wechatUserId) { this.wechatUserId = wechatUserId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public byte[] getContent() { return content == null ? new byte[0] : content.clone(); }
    public void setContent(byte[] content) { this.content = content == null ? new byte[0] : content.clone(); }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
