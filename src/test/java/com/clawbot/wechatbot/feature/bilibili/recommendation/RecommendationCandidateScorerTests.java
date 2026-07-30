package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationCandidateScorerTests {

    @Test
    void scoreAndRankReturnsTopNItems() {
        List<BilibiliContent> candidates = List.of(
            content("c1", 9.5, 1_000_000L, Set.of("科幻", "热血")),
            content("c2", 8.0, 2_000_000L, Set.of("恋爱")),
            content("c3", 7.0, 500_000L, Set.of("日常")));

        List<BilibiliContent> result = RecommendationCandidateScorer.scoreAndRank(
            candidates, 2, Set.of());

        assertEquals(2, result.size());
    }

    @Test
    void higherRatingScoresHigherWhenViewsSimilar() {
        List<BilibiliContent> candidates = List.of(
            content("low-rating", 7.0, 1_000_000L, Set.of()),
            content("high-rating", 9.5, 1_000_000L, Set.of()));

        List<BilibiliContent> result = RecommendationCandidateScorer.scoreAndRank(
            candidates, 2, Set.of());

        assertEquals("high-rating", result.get(0).getContentId());
        assertEquals("low-rating", result.get(1).getContentId());
    }

    @Test
    void genreMatchBoostsScore() {
        List<BilibiliContent> candidates = List.of(
            content("no-match", 9.0, 1_000_000L, Set.of("恋爱", "日常")),
            content("with-match", 8.0, 1_000_000L, Set.of("科幻", "热血")));

        // 用户偏好 "科幻", "热血" → with-match 有题材加分
        List<BilibiliContent> result = RecommendationCandidateScorer.scoreAndRank(
            candidates, 2, Set.of("科幻", "热血"));

        assertEquals("with-match", result.get(0).getContentId());
    }

    @Test
    void returnsEmptyListForNullCandidates() {
        assertTrue(RecommendationCandidateScorer.scoreAndRank(null, 3, Set.of()).isEmpty());
    }

    @Test
    void returnsEmptyListForEmptyCandidates() {
        assertTrue(RecommendationCandidateScorer.scoreAndRank(List.of(), 3, Set.of()).isEmpty());
    }

    @Test
    void lessThanCountAvailableReturnsAll() {
        List<BilibiliContent> candidates = List.of(
            content("c1", 9.0, 100L, Set.of()));

        List<BilibiliContent> result = RecommendationCandidateScorer.scoreAndRank(
            candidates, 5, Set.of());

        assertEquals(1, result.size());
    }

    @Test
    void normalizeRatingReturnsDefaultForNull() {
        assertEquals(0.7, RecommendationCandidateScorer.normalizeRating(null), 0.001);
    }

    @Test
    void normalizeRatingClampsToRange() {
        assertEquals(0.0, RecommendationCandidateScorer.normalizeRating(0.0), 0.001);
        assertEquals(0.5, RecommendationCandidateScorer.normalizeRating(5.0), 0.001);
        assertEquals(1.0, RecommendationCandidateScorer.normalizeRating(10.0), 0.001);
        assertEquals(1.0, RecommendationCandidateScorer.normalizeRating(12.0), 0.001);
    }

    @Test
    void normalizePopularityReturnsDefaultForNullViewCount() {
        assertEquals(0.5, RecommendationCandidateScorer.normalizePopularity(null, 1000), 0.001);
    }

    @Test
    void genreMatchReturnsZeroForEmptyUserPreferences() {
        assertEquals(0.0,
            RecommendationCandidateScorer.computeGenreMatch(Set.of("科幻"), Set.of()), 0.001);
        assertEquals(0.0,
            RecommendationCandidateScorer.computeGenreMatch(Set.of("科幻"), null), 0.001);
    }

    @Test
    void genreMatchCalculatesCorrectRatio() {
        assertEquals(0.5,
            RecommendationCandidateScorer.computeGenreMatch(
                Set.of("科幻", "恋爱"), Set.of("科幻", "热血")), 0.001);
        assertEquals(1.0,
            RecommendationCandidateScorer.computeGenreMatch(
                Set.of("科幻", "热血"), Set.of("科幻", "热血")), 0.001);
        assertEquals(0.0,
            RecommendationCandidateScorer.computeGenreMatch(
                Set.of("恋爱"), Set.of("科幻", "热血")), 0.001);
    }

    @Test
    void generateReasonIncludesRating() {
        BilibiliContent c = content("c1", 9.2, 100L, Set.of("科幻"));
        String reason = RecommendationCandidateScorer.generateReason(c, Set.of());
        assertTrue(reason.contains("9.2"));
    }

    @Test
    void generateReasonIncludesGenreMatch() {
        BilibiliContent c = content("c1", 9.2, 100L, Set.of("科幻", "热血"));
        String reason = RecommendationCandidateScorer.generateReason(c, Set.of("科幻"));
        assertTrue(reason.contains("题材匹配"));
    }

    // ---- helper ----

    private static BilibiliContent content(
            String contentId, double rating, long views, Set<String> genres) {
        BilibiliContent c = new BilibiliContent(ContentType.BANGUMI, contentId, "作品" + contentId);
        c.setRating(rating);
        c.setViewCount(views);
        c.setGenres(new LinkedHashSet<>(genres));
        return c;
    }
}
