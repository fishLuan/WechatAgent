package com.clawbot.wechatbot.feature.bilibili.config;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/** B 站动漫、剧集和电影功能的统一配置入口。 */
@Component
@ConfigurationProperties(prefix = "clawbot.bilibili")
public class BilibiliProperties {
    private boolean enabled;
    private int checkIntervalMinutes = 30;
    private int requestTimeoutSeconds = 15;
    private int maxRetries = 2;
    private LocalTime defaultPushTime = LocalTime.of(20, 0);
    private int defaultRecommendationCount = 3;
    private double defaultMinimumRating = 9.0;
    private LocalTime moviePushTime = LocalTime.of(19, 30);
    private int movieRecommendationCount = 3;
    private double movieMinimumRating = 8.0;
    private int searchResultCount = 5;

    @PostConstruct
    void validate() {
        requirePositive(checkIntervalMinutes, "check-interval-minutes");
        requirePositive(requestTimeoutSeconds, "request-timeout-seconds");
        if (maxRetries < 0) {
            throw invalid("max-retries", "不能小于 0");
        }
        requirePositive(defaultRecommendationCount, "default-recommendation-count");
        requireRating(defaultMinimumRating, "default-minimum-rating");
        requirePositive(movieRecommendationCount, "movie-recommendation-count");
        requirePositive(searchResultCount, "search-result-count");
        requireRating(movieMinimumRating, "movie-minimum-rating");
        if (defaultPushTime == null) {
            throw invalid("default-push-time", "不能为空");
        }
        if (moviePushTime == null) {
            throw invalid("movie-push-time", "不能为空");
        }
    }

    private void requirePositive(int value, String name) {
        if (value < 1) throw invalid(name, "必须大于 0");
    }

    private void requireRating(double value, String name) {
        if (value < 0 || value > 10) {
            throw invalid(name, "必须在 0 到 10 之间");
        }
    }

    private IllegalStateException invalid(String name, String reason) {
        return new IllegalStateException("clawbot.bilibili." + name + " " + reason);
    }

    public int recommendationCount(ContentType contentType) {
        return contentType == ContentType.MOVIE
            ? movieRecommendationCount
            : defaultRecommendationCount;
    }

    public double minimumRating(ContentType contentType) {
        return contentType == ContentType.MOVIE
            ? movieMinimumRating
            : defaultMinimumRating;
    }

    public LocalTime pushTime(ContentType contentType) {
        return contentType == ContentType.MOVIE ? moviePushTime : defaultPushTime;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCheckIntervalMinutes() { return checkIntervalMinutes; }
    public void setCheckIntervalMinutes(int value) { this.checkIntervalMinutes = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { this.requestTimeoutSeconds = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public LocalTime getDefaultPushTime() { return defaultPushTime; }
    public void setDefaultPushTime(LocalTime value) { this.defaultPushTime = value; }
    public int getDefaultRecommendationCount() { return defaultRecommendationCount; }
    public void setDefaultRecommendationCount(int value) {
        this.defaultRecommendationCount = value;
    }
    public double getDefaultMinimumRating() { return defaultMinimumRating; }
    public void setDefaultMinimumRating(double value) { this.defaultMinimumRating = value; }
    public LocalTime getMoviePushTime() { return moviePushTime; }
    public void setMoviePushTime(LocalTime value) { this.moviePushTime = value; }
    public int getMovieRecommendationCount() { return movieRecommendationCount; }
    public void setMovieRecommendationCount(int value) {
        this.movieRecommendationCount = value;
    }
    public double getMovieMinimumRating() { return movieMinimumRating; }
    public void setMovieMinimumRating(double value) { this.movieMinimumRating = value; }
    public int getSearchResultCount() { return searchResultCount; }
    public void setSearchResultCount(int value) { this.searchResultCount = value; }
}
