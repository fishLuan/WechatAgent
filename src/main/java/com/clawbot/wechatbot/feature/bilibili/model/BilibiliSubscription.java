package com.clawbot.wechatbot.feature.bilibili.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** 用户对动漫或连载剧集的追更订阅。 */
@Document(collection = "bilibili_subscription")
@CompoundIndex(
    name = "uk_bilibili_subscription_user_season",
    def = "{'wechatUserId': 1, 'seasonId': 1}",
    unique = true
)
public class BilibiliSubscription {
    @Id
    private String id;
    private String wechatUserId;
    private ContentType contentType;
    private String contentId;
    private String seasonId;
    private String title;
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;
    private String lastKnownEpisodeId;
    private Integer lastKnownEpisodeNumber;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastCheckedAt;
    private Instant lastNotifiedAt;

    public BilibiliSubscription() {
    }

    public BilibiliSubscription(
        String wechatUserId,
        ContentType contentType,
        String contentId,
        String seasonId
    ) {
        if (contentType == null || !contentType.isEpisodeTrackable()) {
            throw new IllegalArgumentException("只有动漫或连载剧集可以创建追更订阅");
        }
        this.wechatUserId = requireText(wechatUserId, "wechatUserId");
        this.contentType = contentType;
        this.contentId = requireText(contentId, "contentId");
        this.seasonId = requireText(seasonId, "seasonId");
        Instant now = Instant.now();
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
    public String getSeasonId() { return seasonId; }
    public void setSeasonId(String value) { this.seasonId = value; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus value) { this.status = value; }
    public String getLastKnownEpisodeId() { return lastKnownEpisodeId; }
    public void setLastKnownEpisodeId(String value) { this.lastKnownEpisodeId = value; }
    public Integer getLastKnownEpisodeNumber() { return lastKnownEpisodeNumber; }
    public void setLastKnownEpisodeNumber(Integer value) {
        this.lastKnownEpisodeNumber = value;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant value) { this.lastCheckedAt = value; }
    public Instant getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(Instant value) { this.lastNotifiedAt = value; }
}
