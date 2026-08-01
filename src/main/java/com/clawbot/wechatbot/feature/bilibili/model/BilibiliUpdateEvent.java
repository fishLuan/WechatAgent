package com.clawbot.wechatbot.feature.bilibili.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** 检测到新剧集后生成的幂等通知事件。 */
@Document(collection = "bilibili_update_event")
@CompoundIndex(
    name = "uk_bilibili_update_subscription_episode",
    def = "{'subscriptionId': 1, 'episodeId': 1}",
    unique = true
)
public class BilibiliUpdateEvent {
    @Id
    private String id;
    private String subscriptionId;
    private String wechatUserId;
    private String episodeId;
    private Integer episodeNumber;
    private String episodeTitle;
    private String episodeUrl;
    private UpdateEventStatus status = UpdateEventStatus.PENDING;
    private String failureReason;
    private int deliveryAttempts;
    private Instant nextAttemptAt;
    private Instant detectedAt;
    private Instant notifiedAt;
    private Instant updatedAt;

    public BilibiliUpdateEvent() {
    }

    public BilibiliUpdateEvent(
        String subscriptionId, String wechatUserId, String episodeId
    ) {
        this.subscriptionId = requireText(subscriptionId, "subscriptionId");
        this.wechatUserId = requireText(wechatUserId, "wechatUserId");
        this.episodeId = requireText(episodeId, "episodeId");
        Instant now = Instant.now();
        this.detectedAt = now;
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
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String value) { this.subscriptionId = value; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String value) { this.wechatUserId = value; }
    public String getEpisodeId() { return episodeId; }
    public void setEpisodeId(String value) { this.episodeId = value; }
    public Integer getEpisodeNumber() { return episodeNumber; }
    public void setEpisodeNumber(Integer value) { this.episodeNumber = value; }
    public String getEpisodeTitle() { return episodeTitle; }
    public void setEpisodeTitle(String value) { this.episodeTitle = value; }
    public String getEpisodeUrl() { return episodeUrl; }
    public void setEpisodeUrl(String value) { this.episodeUrl = value; }
    public UpdateEventStatus getStatus() { return status; }
    public void setStatus(UpdateEventStatus value) { this.status = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(int value) { this.deliveryAttempts = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { this.nextAttemptAt = value; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant value) { this.detectedAt = value; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant value) { this.notifiedAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
