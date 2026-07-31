package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationState;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagContext;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagRequest;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliPreferenceRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliRagContextBuilderTests {

    @Test
    void buildsContextForCurrentWechatUserOnly() {
        BilibiliContentRepository contentRepository =
            mock(BilibiliContentRepository.class);
        BilibiliPreferenceRepository preferenceRepository =
            mock(BilibiliPreferenceRepository.class);
        BilibiliRecommendationHistoryRepository historyRepository =
            mock(BilibiliRecommendationHistoryRepository.class);
        BilibiliSubscriptionRepository subscriptionRepository =
            mock(BilibiliSubscriptionRepository.class);
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, "media-1", "葬送的芙莉莲");
        content.setRating(9.8);
        when(contentRepository
            .findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
                ContentType.BANGUMI, 0))
            .thenReturn(List.of(content));
        BilibiliPreference preference =
            new BilibiliPreference("user-1", ContentType.BANGUMI);
        preference.setMinimumRating(9.0);
        preference.setRecommendationCount(3);
        when(preferenceRepository.findByWechatUserIdAndContentType(
            "user-1", ContentType.BANGUMI))
            .thenReturn(Optional.of(preference));
        when(subscriptionRepository.findByWechatUserIdAndStatus(
            "user-1", SubscriptionStatus.ACTIVE))
            .thenReturn(List.of());
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
            "user-1", ContentType.BANGUMI, List.of(RecommendationState.WANT_TO_WATCH)))
            .thenReturn(List.of());
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
            "user-1", ContentType.BANGUMI, List.of(RecommendationState.WATCHED)))
            .thenReturn(List.of());
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
            "user-1", ContentType.BANGUMI, List.of(RecommendationState.DISLIKED)))
            .thenReturn(List.of());

        BilibiliRagContextBuilder builder = new BilibiliRagContextBuilder(
            new BilibiliRagRetriever(contentRepository),
            preferenceRepository,
            historyRepository,
            subscriptionRepository);

        BilibiliRagContext context = builder.build(new BilibiliRagRequest(
            "user-1", "智能推荐动漫", ContentType.BANGUMI, null));

        assertFalse(context.empty());
        assertTrue(context.userContext().contains("最低评分：9.0"));
        verify(preferenceRepository).findByWechatUserIdAndContentType(
            "user-1", ContentType.BANGUMI);
        verify(subscriptionRepository).findByWechatUserIdAndStatus(
            "user-1", SubscriptionStatus.ACTIVE);
    }

    @Test
    void filtersMovieLikeContentFromBangumiRetrieval() {
        BilibiliContentRepository contentRepository =
            mock(BilibiliContentRepository.class);
        BilibiliContent movieLike =
            new BilibiliContent(ContentType.BANGUMI, "movie-like", "紫罗兰永恒花园 剧场版");
        movieLike.setRating(9.9);
        movieLike.setPageUrl("https://www.bilibili.com/bangumi/play/ss40028?theme=movie");
        movieLike.setLatestEpisodeNumber(1);
        movieLike.setFinished(true);
        BilibiliContent bangumi =
            new BilibiliContent(ContentType.BANGUMI, "bangumi-1", "紫罗兰永恒花园");
        bangumi.setRating(9.8);
        bangumi.getGenres().add("治愈");
        when(contentRepository
            .findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
                ContentType.BANGUMI, 0))
            .thenReturn(List.of(movieLike, bangumi));

        List<?> results = new BilibiliRagRetriever(contentRepository).retrieve(
            "推荐类似紫罗兰的番", ContentType.BANGUMI, null, 5);

        assertEquals(1, results.size());
        assertTrue(results.toString().contains("紫罗兰永恒花园"));
        assertFalse(results.toString().contains("剧场版"));
    }
}
