package com.clawbot.wechatbot.feature.bilibili.config;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BilibiliPropertiesTests {

    @Test
    void providesSafeDefaultsForAnIncompleteFeature() {
        BilibiliProperties properties = new BilibiliProperties();

        assertDoesNotThrow(properties::validate);
        assertEquals(3, properties.getDefaultRecommendationCount());
        assertEquals(9.0, properties.getDefaultMinimumRating());
        assertEquals(LocalTime.of(20, 0), properties.getDefaultPushTime());
        assertEquals(3, properties.getMovieRecommendationCount());
        assertEquals(8.0, properties.getMovieMinimumRating());
        assertEquals(LocalTime.of(19, 30), properties.getMoviePushTime());
    }

    @Test
    void keepsAnimeAndMovieRecommendationSettingsIndependent() {
        BilibiliProperties properties = new BilibiliProperties();
        properties.setDefaultRecommendationCount(5);
        properties.setDefaultMinimumRating(9.2);
        properties.setMovieRecommendationCount(2);
        properties.setMovieMinimumRating(7.8);

        properties.validate();

        assertEquals(5, properties.getDefaultRecommendationCount());
        assertEquals(9.2, properties.getDefaultMinimumRating());
        assertEquals(2, properties.getMovieRecommendationCount());
        assertEquals(7.8, properties.getMovieMinimumRating());
        assertEquals(5, properties.recommendationCount(ContentType.BANGUMI));
        assertEquals(2, properties.recommendationCount(ContentType.MOVIE));
        assertEquals(9.2, properties.minimumRating(ContentType.SERIES));
        assertEquals(7.8, properties.minimumRating(ContentType.MOVIE));
    }

    @Test
    void rejectsInvalidRatingAndRetryConfiguration() {
        BilibiliProperties invalidRating = new BilibiliProperties();
        invalidRating.setMovieMinimumRating(10.1);
        assertThrows(IllegalStateException.class, invalidRating::validate);

        BilibiliProperties invalidRetries = new BilibiliProperties();
        invalidRetries.setMaxRetries(-1);
        assertThrows(IllegalStateException.class, invalidRetries::validate);
    }
}
