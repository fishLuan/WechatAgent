package com.clawbot.wechatbot.feature.bilibili.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliModelTests {

    @Test
    void onlyBangumiAndSeriesCanTrackEpisodes() {
        assertTrue(ContentType.BANGUMI.isEpisodeTrackable());
        assertTrue(ContentType.SERIES.isEpisodeTrackable());
        assertFalse(ContentType.MOVIE.isEpisodeTrackable());
        assertFalse(ContentType.UPLOADER.isEpisodeTrackable());
    }

    @Test
    void rejectsMovieAsEpisodeSubscription() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new BilibiliSubscription(
                "wechat-user", ContentType.MOVIE, "movie-1", "season-1"));
    }

    @Test
    void initializesTrackableSubscriptionAndRecommendationHistory() {
        BilibiliSubscription subscription = new BilibiliSubscription(
            "wechat-user", ContentType.BANGUMI, "media-1", "season-1");
        BilibiliRecommendationHistory history =
            new BilibiliRecommendationHistory(
                "wechat-user", ContentType.MOVIE, "movie-1");

        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertNotNull(subscription.getCreatedAt());
        assertEquals(RecommendationState.RECOMMENDED, history.getState());
        assertNotNull(history.getFirstRecommendedAt());
    }

    @Test
    void copiesMutableGenreCollections() {
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, "media-1", "测试番剧");
        Set<String> genres = new java.util.LinkedHashSet<>(Set.of("科幻"));

        content.setGenres(genres);
        genres.add("喜剧");

        assertEquals(Set.of("科幻"), content.getGenres());
    }

    @Test
    void usesRequiredMongoCollectionNames() {
        assertEquals(
            "bilibili_content",
            BilibiliContent.class.getAnnotation(Document.class).collection());
        assertEquals(
            "bilibili_subscription",
            BilibiliSubscription.class.getAnnotation(Document.class).collection());
        assertEquals(
            "bilibili_update_event",
            BilibiliUpdateEvent.class.getAnnotation(Document.class).collection());
        assertEquals(
            "bilibili_preference",
            BilibiliPreference.class.getAnnotation(Document.class).collection());
        assertEquals(
            "bilibili_recommendation_history",
            BilibiliRecommendationHistory.class.getAnnotation(Document.class).collection());
    }
}
