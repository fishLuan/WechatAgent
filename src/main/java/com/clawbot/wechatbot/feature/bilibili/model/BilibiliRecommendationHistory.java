package com.clawbot.wechatbot.feature.bilibili.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** 推荐记录及用户对作品的后续状态。 */
@Document(collection = "bilibili_recommendation_history")
@CompoundIndex(
    name = "uk_bilibili_history_user_type_content",
    def = "{'wechatUserId': 1, 'contentType': 1, 'contentId': 1}",
    unique = true
)
public class BilibiliRecommendationHistory {
    @Id
    private String id;
    private String wechatUserId;
    private ContentType contentType;
    private String contentId;
    private String title;
    private RecommendationState state = RecommendationState.RECOMMENDED;
    private Instant firstRecommendedAt;
    private Instant lastRecommendedAt;
    private Instant stateChangedAt;
    private int recommendationCount = 1;
    private Instant createdAt;
    private Instant updatedAt;

    public BilibiliRecommendationHistory() {
    }

    public BilibiliRecommendationHistory(
        String wechatUserId, ContentType contentType, String contentId
    ) {
        this.wechatUserId = requireText(wechatUserId, "wechatUserId");
        if (contentType == null) {
            throw new IllegalArgumentException("contentType 不能为空");
        }
        this.contentType = contentType;
        this.contentId = requireText(contentId, "contentId");
        Instant now = Instant.now();
        this.firstRecommendedAt = now;
        this.lastRecommendedAt = now;
        this.stateChangedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String value) { this.wechatUserId = value; }
    public ContentType getContentType() { return contentType; }
    public void setContentType(ContentType value) { this.contentType = value; }
    public String getContentId() { return contentId; }
    public void setContentId(String value) { this.contentId = value; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public RecommendationState getState() { return state; }
    public void setState(RecommendationState value) { this.state = value; }
    public Instant getFirstRecommendedAt() { return firstRecommendedAt; }
    public void setFirstRecommendedAt(Instant value) { this.firstRecommendedAt = value; }
    public Instant getLastRecommendedAt() { return lastRecommendedAt; }
    public void setLastRecommendedAt(Instant value) { this.lastRecommendedAt = value; }
    public Instant getStateChangedAt() { return stateChangedAt; }
    public void setStateChangedAt(Instant value) { this.stateChangedAt = value; }
    public int getRecommendationCount() { return recommendationCount; }
    public void setRecommendationCount(int value) { this.recommendationCount = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
