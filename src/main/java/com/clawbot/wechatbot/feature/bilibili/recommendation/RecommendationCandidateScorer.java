package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐候选打分排序引擎。
 *
 * <p>使用综合评分公式对候选作品进行排序：<br>
 * 评分权重 60% + 热度权重 30% + 用户偏好题材匹配 10%<br>
 * 所有单项分值归一化到 0-1 范围。</p>
 */
public final class RecommendationCandidateScorer {

    /** 评分权重 */
    static final double RATING_WEIGHT = 0.35;
    /** 热度权重 */
    static final double POPULARITY_WEIGHT = 0.05;
    /** 题材匹配权重 */
    static final double GENRE_MATCH_WEIGHT = 0.2;
    /** 标签匹配权重 */
    static final double TAG_MATCH_WEIGHT = 0.4;

    private RecommendationCandidateScorer() {
    }

    /**
     * 对候选作品集合打分并排序，返回前 N 名。
     *
     * @param candidates   候选作品列表（需确保 rating 字段有值）
     * @param count        返回条数
     * @param userGenres  用户偏好题材（可能为空）
     * @return 按综合分降序排列的前 N 部作品
     */
    public static List<BilibiliContent> scoreAndRank(
            List<BilibiliContent> candidates,
            int count,
            Set<String> userGenres) {

        if (candidates == null || candidates.isEmpty() || count < 1) {
            return List.of();
        }

        Set<String> genres = (userGenres == null) ? Set.of() : Set.copyOf(userGenres);
        long maxViewCount = findMaxViewCount(candidates);

        return candidates.stream()
            .map(c -> new ScoredCandidate(c, computeScore(c, maxViewCount, genres)))
            .sorted(Comparator.comparingDouble((ScoredCandidate s) -> s.score).reversed())
            .limit(count)
            .map(s -> s.content)
            .collect(Collectors.toList());
    }

    // ---- scoring internals ----

    static double computeScore(
            BilibiliContent content, long maxViewCount, Set<String> userGenres) {

        double ratingScore = normalizeRating(content.getRating());
        double popularityScore = normalizePopularity(content.getViewCount(), maxViewCount);
        double genreScore = computeGenreMatch(content.getGenres(), userGenres);

        return ratingScore * RATING_WEIGHT
             + popularityScore * POPULARITY_WEIGHT
             + genreScore * GENRE_MATCH_WEIGHT;
    }

    /**
     * 对候选打原始分与推荐理由的描述。
     * 用于 {@link com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent#recommendationReason()}。
     */
    static String generateReason(
            BilibiliContent content, Set<String> userGenres) {
        if (content.getRating() == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("评分 ").append(content.getRating());

        if (!userGenres.isEmpty()) {
            long matched = content.getGenres().stream()
                .filter(userGenres::contains)
                .count();
            if (matched > 0) {
                sb.append("，题材匹配");
            }
        }
        return sb.toString();
    }

    // 评分归一化（0-10 → 0-1）
    static double normalizeRating(Double rating) {
        if (rating == null) return 0.7; // 无评分的给默认分 0.7
        return Math.min(1.0, Math.max(0, rating / 10.0));
    }

    // 热度归一化：取对数后除以最大值的对数，无播放量时给中位值
    static double normalizePopularity(Long viewCount, long maxViewCount) {
        if (viewCount == null || viewCount <= 0) return 0.5;
        if (maxViewCount <= 0) return 0.5;
        double logView = Math.log(viewCount + 1);
        double logMax = Math.log(maxViewCount + 1);
        return logMax > 0 ? Math.min(1.0, logView / logMax) : 0.5;
    }

    // 题材匹配度：用户偏好题材中命中的比例
    static double computeGenreMatch(Set<String> contentGenres, Set<String> userGenres) {
        if (userGenres == null || userGenres.isEmpty()) return 0;
        if (contentGenres == null || contentGenres.isEmpty()) return 0;
        long matched = contentGenres.stream()
            .filter(userGenres::contains)
            .count();
        return Math.min(1.0, (double) matched / userGenres.size());
    }

    // ---- 支持 tags 的重载方法 ----

    /** 带标签偏好的打分排序。 */
    public static List<BilibiliContent> scoreAndRank(
            List<BilibiliContent> candidates, int count,
            Set<String> userGenres, Set<String> userTags) {

        if (candidates == null || candidates.isEmpty() || count < 1) return List.of();
        Set<String> genres = userGenres == null ? Set.of() : Set.copyOf(userGenres);
        Set<String> tags = userTags == null ? Set.of() : Set.copyOf(userTags);
        long maxViewCount = findMaxViewCount(candidates);

        return candidates.stream()
            .map(c -> new ScoredCandidate(c, computeScore(c, maxViewCount, genres, tags)))
            .sorted(Comparator.comparingDouble((ScoredCandidate s) -> s.score).reversed())
            .limit(count)
            .map(s -> s.content)
            .collect(Collectors.toList());
    }

    static double computeScore(BilibiliContent content, long maxViewCount,
                               Set<String> userGenres, Set<String> userTags) {
        double ratingScore = normalizeRating(content.getRating());
        double popularityScore = normalizePopularity(content.getViewCount(), maxViewCount);
        double genreScore = computeGenreMatch(content.getGenres(), userGenres);
        double tagScore = computeTagMatch(content.getTags(), userTags);

        return ratingScore * RATING_WEIGHT
             + popularityScore * POPULARITY_WEIGHT
             + genreScore * GENRE_MATCH_WEIGHT
             + tagScore * TAG_MATCH_WEIGHT;
    }

    static String generateReason(BilibiliContent content,
                                 Set<String> userGenres, Set<String> userTags) {
        if (content.getRating() == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("评分 ").append(content.getRating());
        if (!userGenres.isEmpty()) {
            long matched = content.getGenres().stream().filter(userGenres::contains).count();
            if (matched > 0) sb.append("，题材匹配");
        }
        if (!userTags.isEmpty()) {
            long matched = content.getTags().stream().filter(userTags::contains).count();
            if (matched > 0) sb.append("，标签匹配");
        }
        return sb.toString();
    }

    /** 标签匹配度：用户偏好标签中命中的比例。 */
    static double computeTagMatch(Set<String> contentTags, Set<String> userTags) {
        if (userTags == null || userTags.isEmpty()) return 0;
        if (contentTags == null || contentTags.isEmpty()) return 0;
        long matched = contentTags.stream().filter(userTags::contains).count();
        return Math.min(1.0, (double) matched / userTags.size());
    }

    private static long findMaxViewCount(List<BilibiliContent> candidates) {
        return candidates.stream()
            .mapToLong(c -> c.getViewCount() == null ? 0L : c.getViewCount())
            .max()
            .orElse(0L);
    }

    // ---- internal DTO ----

    private record ScoredCandidate(BilibiliContent content, double score) {
    }
}
