package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilmSeriesRecommendationScorerTests {

    @Test
    void weightedMoviePreferenceCanOutrankRatingOnlyCandidate() {
        BilibiliContent preferred = content(
            ContentType.MOVIE, "preferred", 8.8, Set.of("科幻"));
        BilibiliContent highRated = content(
            ContentType.MOVIE, "rating", 9.8, Set.of("爱情"));

        List<BilibiliContent> result = FilmSeriesRecommendationScorer.scoreAndRank(
            ContentType.MOVIE, List.of(highRated, preferred), 2,
            Set.of(), Set.of("科幻", "爱情"), Map.of("科幻", 5, "爱情", 1));

        assertThat(result.getFirst().getContentId()).isEqualTo("preferred");
    }

    @Test
    void seriesRankingDropsCandidatesFromOtherContentTypes() {
        BilibiliContent series = content(
            ContentType.SERIES, "series", 8.0, Set.of("悬疑"));
        BilibiliContent movie = content(
            ContentType.MOVIE, "movie", 10.0, Set.of("悬疑"));

        List<BilibiliContent> result = FilmSeriesRecommendationScorer.scoreAndRank(
            ContentType.SERIES, List.of(movie, series), 3,
            Set.of(), Set.of("悬疑"), Map.of("悬疑", 3));

        assertThat(result).extracting(BilibiliContent::getContentId)
            .containsExactly("series");
    }

    @Test
    void scorerCannotAffectBangumiPath() {
        assertThatThrownBy(() -> FilmSeriesRecommendationScorer.scoreAndRank(
            ContentType.BANGUMI, List.of(), 3, Set.of(), Set.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private BilibiliContent content(
        ContentType type, String id, double rating, Set<String> tags
    ) {
        BilibiliContent content = new BilibiliContent(type, id, id);
        content.setRating(rating);
        content.setTags(tags);
        content.setGenres(Set.of());
        content.setViewCount(100L);
        return content;
    }
}
