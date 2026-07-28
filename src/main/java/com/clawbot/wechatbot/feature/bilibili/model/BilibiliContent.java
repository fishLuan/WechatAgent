package com.clawbot.wechatbot.feature.bilibili.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** 供采集、推荐和订阅模块共同使用的 B 站作品快照。 */
@Document(collection = "bilibili_content")
@CompoundIndex(
    name = "uk_bilibili_content_type_content",
    def = "{'contentType': 1, 'contentId': 1}",
    unique = true
)
public class BilibiliContent {
    @Id
    private String id;
    private ContentType contentType;
    private String contentId;
    private String seasonId;
    private String title;
    private String description;
    private Set<String> genres = new LinkedHashSet<>();
    private Double rating;
    private Long viewCount;
    private String coverUrl;
    private String pageUrl;
    private String latestEpisodeId;
    private String latestEpisodeTitle;
    private Integer latestEpisodeNumber;
    private boolean finished;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastFetchedAt;

    public BilibiliContent() {
    }

    public BilibiliContent(ContentType contentType, String contentId, String title) {
        this.contentType = requireType(contentType);
        this.contentId = requireText(contentId, "contentId");
        this.title = requireText(title, "title");
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    private static ContentType requireType(ContentType value) {
        if (value == null) throw new IllegalArgumentException("contentType 不能为空");
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

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
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Set<String> getGenres() {
        if (genres == null) genres = new LinkedHashSet<>();
        return genres;
    }
    public void setGenres(Set<String> genres) {
        this.genres = genres == null ? new LinkedHashSet<>() : new LinkedHashSet<>(genres);
    }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public String getLatestEpisodeId() { return latestEpisodeId; }
    public void setLatestEpisodeId(String value) { this.latestEpisodeId = value; }
    public String getLatestEpisodeTitle() { return latestEpisodeTitle; }
    public void setLatestEpisodeTitle(String value) { this.latestEpisodeTitle = value; }
    public Integer getLatestEpisodeNumber() { return latestEpisodeNumber; }
    public void setLatestEpisodeNumber(Integer value) { this.latestEpisodeNumber = value; }
    public boolean isFinished() { return finished; }
    public void setFinished(boolean finished) { this.finished = finished; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastFetchedAt() { return lastFetchedAt; }
    public void setLastFetchedAt(Instant lastFetchedAt) { this.lastFetchedAt = lastFetchedAt; }
}
