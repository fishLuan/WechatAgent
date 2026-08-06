package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Personalized scorer dedicated to SERIES and MOVIE; never handles BANGUMI. */
final class FilmSeriesRecommendationScorer {
    private static final double RATING_WEIGHT = 0.30D;
    private static final double POPULARITY_WEIGHT = 0.05D;
    private static final double GENRE_WEIGHT = 0.20D;
    private static final double TAG_WEIGHT = 0.45D;

    private FilmSeriesRecommendationScorer() {
    }

    static List<BilibiliContent> scoreAndRank(
        ContentType contentType,
        List<BilibiliContent> candidates,
        int count,
        Set<String> preferredGenres,
        Set<String> preferredTags,
        Map<String, Integer> tagWeights
    ) {
        requireFilmOrSeries(contentType);
        if (candidates == null || candidates.isEmpty() || count < 1) {
            return List.of();
        }
        Set<String> genres = preferredGenres == null ? Set.of() : preferredGenres;
        Set<String> tags = preferredTags == null ? Set.of() : preferredTags;
        Map<String, Integer> weights = tagWeights == null ? Map.of() : tagWeights;
        List<BilibiliContent> sameType = candidates.stream()
            .filter(item -> item != null && item.getContentType() == contentType)
            .toList();
        long maxViews = sameType.stream()
            .mapToLong(item -> item.getViewCount() == null ? 0L : item.getViewCount())
            .max().orElse(0L);
        return sameType.stream()
            .map(item -> new Scored(item, score(
                item, maxViews, genres, tags, weights)))
            .sorted(Comparator.comparingDouble(Scored::score).reversed())
            .limit(count)
            .map(Scored::content)
            .toList();
    }

    static String reason(
        ContentType contentType,
        BilibiliContent content,
        Set<String> preferredGenres,
        Set<String> preferredTags,
        Map<String, Integer> tagWeights
    ) {
        requireFilmOrSeries(contentType);
        StringBuilder reason = new StringBuilder();
        if (content.getRating() != null) {
            reason.append("评分 ").append(content.getRating());
        }
        Set<String> genres = preferredGenres == null ? Set.of() : preferredGenres;
        Set<String> tags = preferredTags == null ? Set.of() : preferredTags;
        Map<String, Integer> weights = tagWeights == null ? Map.of() : tagWeights;
        List<String> matchedGenres = safe(content.getGenres()).stream()
            .filter(genres::contains).toList();
        List<String> matchedTags = safe(content.getTags()).stream()
            .filter(tags::contains)
            .sorted(Comparator.comparingInt(
                (String tag) -> positiveWeight(weights, tag)).reversed())
            .toList();
        append(reason, "题材匹配", matchedGenres);
        append(reason, "偏好匹配", matchedTags);
        return reason.toString();
    }

    private static double score(
        BilibiliContent content,
        long maxViews,
        Set<String> genres,
        Set<String> tags,
        Map<String, Integer> weights
    ) {
        return RecommendationCandidateScorer.normalizeRating(content.getRating())
                * RATING_WEIGHT
            + RecommendationCandidateScorer.normalizePopularity(
                content.getViewCount(), maxViews) * POPULARITY_WEIGHT
            + RecommendationCandidateScorer.computeGenreMatch(
                content.getGenres(), genres) * GENRE_WEIGHT
            + weightedTagMatch(content.getTags(), tags, weights) * TAG_WEIGHT;
    }

    static double weightedTagMatch(
        Set<String> contentTags,
        Set<String> preferredTags,
        Map<String, Integer> tagWeights
    ) {
        if (preferredTags == null || preferredTags.isEmpty()
            || contentTags == null || contentTags.isEmpty()) {
            return 0D;
        }
        int total = preferredTags.stream()
            .mapToInt(tag -> positiveWeight(tagWeights, tag)).sum();
        if (total <= 0) return 0D;
        int matched = preferredTags.stream().filter(contentTags::contains)
            .mapToInt(tag -> positiveWeight(tagWeights, tag)).sum();
        return Math.min(1D, (double) matched / total);
    }

    private static int positiveWeight(Map<String, Integer> weights, String tag) {
        if (weights == null) return 1;
        return Math.max(1, weights.getOrDefault(tag, 1));
    }

    private static Set<String> safe(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    private static void append(
        StringBuilder target, String label, List<String> matches
    ) {
        if (matches.isEmpty()) return;
        if (!target.isEmpty()) target.append("，");
        target.append(label).append("：").append(String.join("、", matches));
    }

    private static void requireFilmOrSeries(ContentType type) {
        if (type != ContentType.SERIES && type != ContentType.MOVIE) {
            throw new IllegalArgumentException(
                "电影/电视剧评分器禁止处理内容类型：" + type);
        }
    }

    private record Scored(BilibiliContent content, double score) { }
}
