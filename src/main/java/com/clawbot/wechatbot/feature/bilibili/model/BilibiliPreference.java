package com.clawbot.wechatbot.feature.bilibili.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** 按微信用户和内容类型隔离的推荐偏好。 */
@Document(collection = "bilibili_preference")
@CompoundIndex(
    name = "uk_bilibili_preference_user_type",
    def = "{'wechatUserId': 1, 'contentType': 1}",
    unique = true
)
public class BilibiliPreference {
    @Id
    private String id;
    private String wechatUserId;
    private ContentType contentType;
    private Set<String> preferredGenres = new LinkedHashSet<>();
    private Set<String> preferredTags = new LinkedHashSet<>();
    private java.util.Map<String, Integer> tagWeights = new java.util.LinkedHashMap<>();
    private double minimumRating;
    private int recommendationCount;
    private LocalTime pushTime;
    private boolean pushEnabled = true;
    private Set<DayOfWeek> excludedPushDays = new LinkedHashSet<>();
    private Instant createdAt;
    private Instant updatedAt;

    public BilibiliPreference() {
    }

    public BilibiliPreference(String wechatUserId, ContentType contentType) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("wechatUserId 不能为空");
        }
        if (contentType == null) {
            throw new IllegalArgumentException("contentType 不能为空");
        }
        this.wechatUserId = wechatUserId.trim();
        this.contentType = contentType;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String value) { this.wechatUserId = value; }
    public ContentType getContentType() { return contentType; }
    public void setContentType(ContentType value) { this.contentType = value; }
    public Set<String> getPreferredGenres() {
        if (preferredGenres == null) preferredGenres = new LinkedHashSet<>();
        return preferredGenres;
    }
    public void setPreferredGenres(Set<String> values) {
        preferredGenres =
            values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
    public Set<String> getPreferredTags() {
        if (preferredTags == null) preferredTags = new LinkedHashSet<>();
        return preferredTags;
    }
    public void setPreferredTags(Set<String> values) {
        preferredTags =
            values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
    public java.util.Map<String, Integer> getTagWeights() {
        if (tagWeights == null) tagWeights = new java.util.LinkedHashMap<>();
        return tagWeights;
    }
    public void setTagWeights(java.util.Map<String, Integer> values) {
        tagWeights = values == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(values);
    }
    public double getMinimumRating() { return minimumRating; }
    public void setMinimumRating(double value) { this.minimumRating = value; }
    public int getRecommendationCount() { return recommendationCount; }
    public void setRecommendationCount(int value) { this.recommendationCount = value; }
    public LocalTime getPushTime() { return pushTime; }
    public void setPushTime(LocalTime value) { this.pushTime = value; }
    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean value) { this.pushEnabled = value; }
    public Set<DayOfWeek> getExcludedPushDays() {
        if (excludedPushDays == null) excludedPushDays = new LinkedHashSet<>();
        return excludedPushDays;
    }
    public void setExcludedPushDays(Set<DayOfWeek> values) {
        excludedPushDays =
            values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
