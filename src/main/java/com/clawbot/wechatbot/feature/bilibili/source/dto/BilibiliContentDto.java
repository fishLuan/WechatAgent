package com.clawbot.wechatbot.feature.bilibili.source.dto;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** 采集模块内部使用的 B 站内容 DTO，不暴露给推荐和订阅模块。 */
public class BilibiliContentDto {
    private ContentType contentType;
    private String contentId;
    private String seasonId;
    private String title;
    private String description;
    private Set<String> genres = new LinkedHashSet<>();
    private Set<String> tags = new LinkedHashSet<>();
    private Double rating;
    private Long viewCount;
    private String coverUrl;
    private String pageUrl;
    private BilibiliEpisodeDto latestEpisode;
    private Instant latestEpisodePubTime;
    private boolean finished;

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
    public Set<String> getGenres() { return genres; }
    public void setGenres(Set<String> genres) {
        this.genres = genres == null ? new LinkedHashSet<>() : new LinkedHashSet<>(genres);
    }
    public Set<String> getTags() { return tags; }
    public void setTags(Set<String> tags) {
        this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
    }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public BilibiliEpisodeDto getLatestEpisode() { return latestEpisode; }
    public void setLatestEpisode(BilibiliEpisodeDto latestEpisode) {
        this.latestEpisode = latestEpisode;
    }
    public Instant getLatestEpisodePubTime() { return latestEpisodePubTime; }
    public void setLatestEpisodePubTime(Instant latestEpisodePubTime) {
        this.latestEpisodePubTime = latestEpisodePubTime;
    }
    public boolean isFinished() { return finished; }
    public void setFinished(boolean finished) { this.finished = finished; }
}
