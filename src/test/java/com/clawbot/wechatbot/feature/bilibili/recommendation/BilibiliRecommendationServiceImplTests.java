package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.*;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliPreferenceRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BilibiliRecommendationServiceImplTests {

    @Mock private BilibiliContentSource contentSource;
    @Mock private BilibiliPreferenceRepository prefRepository;
    @Mock private BilibiliRecommendationHistoryRepository historyRepository;
    @Mock private BilibiliContentRepository contentRepository;

    private BilibiliRecommendationServiceImpl service;
    private BilibiliProperties properties;
    private PendingRecommendationStore pendingStore;
    private final String userId = "wechat-user-1";

    @BeforeEach
    void setUp() {
        properties = new BilibiliProperties();
        properties.setDefaultMinimumRating(9.0);
        properties.setDefaultRecommendationCount(3);
        properties.setMovieMinimumRating(8.0);
        properties.setMovieRecommendationCount(3);

        pendingStore = new PendingRecommendationStore();

        BilibiliPreferenceServiceImpl preferenceService =
            new BilibiliPreferenceServiceImpl(prefRepository, properties);
        RecommendationHistoryService historyService =
            new RecommendationHistoryService(historyRepository);

        service = new BilibiliRecommendationServiceImpl(
            contentSource, preferenceService, historyService, pendingStore, properties,
            contentRepository);
        when(contentRepository
            .findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
                any(ContentType.class), anyDouble()))
            .thenReturn(List.of());
    }

    @Test
    void recommendReturnsTopRatedItems() throws Exception {
        // 模拟偏好
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        // 模拟候选
        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(candidates(ContentType.BANGUMI, 10, 9.0));
        // 模拟历史（无排除）
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of());

        RecommendationResult result = service.recommend(userId, ContentType.BANGUMI, 3);

        assertEquals(3, result.items().size());
        assertEquals(userId, result.wechatUserId());
        assertEquals(ContentType.BANGUMI, result.contentType());
    }

    @Test
    void usesMongoSnapshotWithoutCallingLiveSource() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        List<BilibiliContent> snapshots = candidates(ContentType.BANGUMI, 5, 9.0);
        when(contentRepository
            .findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
                ContentType.BANGUMI, 0.0))
            .thenReturn(snapshots);
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of());

        RecommendationResult result = service.recommend(userId, ContentType.BANGUMI, 3);

        assertEquals(3, result.items().size());
        verify(contentSource, never()).findCandidates(any(), anyInt());
    }

    @Test
    void animeAndMovieCandidatePoolsAreIndependent() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        mockPreference(userId, ContentType.MOVIE, 8.0, 3, Set.of());

        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(candidates(ContentType.BANGUMI, 5, 9.0));
        when(contentSource.findCandidates(ContentType.MOVIE, 30))
            .thenReturn(candidates(ContentType.MOVIE, 5, 8.0));

        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                anyString(), any(ContentType.class), anyCollection()))
            .thenReturn(List.of());

        RecommendationResult animeResult = service.recommend(userId, ContentType.BANGUMI, 3);
        RecommendationResult movieResult = service.recommend(userId, ContentType.MOVIE, 3);

        animeResult.items().forEach(i -> assertEquals(ContentType.BANGUMI, i.contentType()));
        movieResult.items().forEach(i -> assertEquals(ContentType.MOVIE, i.contentType()));
    }

    @Test
    void dislikedContentIsExcluded() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(candidates(ContentType.BANGUMI, 5, 9.0));

        // "anime-1" 已标记不喜欢
        BilibiliRecommendationHistory disliked =
            new BilibiliRecommendationHistory(userId, ContentType.BANGUMI, "anime-1");
        disliked.setState(RecommendationState.DISLIKED);
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of(disliked));

        RecommendationResult result = service.recommend(userId, ContentType.BANGUMI, 3);

        assertFalse(result.items().stream()
            .anyMatch(i -> i.contentId().equals("anime-1")),
            "不喜欢的内容不应出现在推荐中");
    }

    @Test
    void findPendingItemByItemNumber() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(candidates(ContentType.BANGUMI, 5, 9.0));
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of());

        service.refresh(userId, ContentType.BANGUMI, 3);

        RecommendedContent item = service.findPendingItem(userId, 1);
        assertNotNull(item);
    }

    @Test
    void refreshGeneratesNewRecommendations() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 1, Set.of());
        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(candidates(ContentType.BANGUMI, 5, 9.0));
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of());

        RecommendationResult first = service.recommend(userId, ContentType.BANGUMI, 1);
        RecommendationResult second = service.refresh(userId, ContentType.BANGUMI, 3);

        assertEquals(1, first.items().size());
        assertEquals(3, second.items().size());
    }

    @Test
    void returnsEmptyResultWhenNoCandidates() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(List.of());

        RecommendationResult result = service.recommend(userId, ContentType.BANGUMI, 3);

        assertTrue(result.items().isEmpty());
    }

    @Test
    void seriesRecommendationUsesDifferentCandidates() throws Exception {
        mockPreference(userId, ContentType.BANGUMI, 9.0, 3, Set.of());
        mockPreference(userId, ContentType.SERIES, 9.0, 3, Set.of());

        when(contentSource.findCandidates(ContentType.BANGUMI, 30))
            .thenReturn(candidates(ContentType.BANGUMI, 5, 9.0));
        when(contentSource.findCandidates(ContentType.SERIES, 30))
            .thenReturn(candidates(ContentType.SERIES, 5, 9.0));
        when(historyRepository.findByWechatUserIdAndContentTypeAndStateIn(
                anyString(), any(ContentType.class), anyCollection()))
            .thenReturn(List.of());

        RecommendationResult anime = service.recommend(userId, ContentType.BANGUMI, 3);
        RecommendationResult series = service.recommend(userId, ContentType.SERIES, 3);

        Set<String> animeIds = new HashSet<>(
            anime.items().stream().map(RecommendedContent::contentId).toList());
        Set<String> seriesIds = new HashSet<>(
            series.items().stream().map(RecommendedContent::contentId).toList());
        assertFalse(animeIds.equals(seriesIds));
    }

    // ---- helper methods ----

    private void mockPreference(
            String userId, ContentType type, double minRating,
            int count, Set<String> genres) {
        BilibiliPreference pref = new BilibiliPreference(userId, type);
        pref.setMinimumRating(minRating);
        pref.setRecommendationCount(count);
        pref.setPreferredGenres(new LinkedHashSet<>(genres));
        when(prefRepository.findByWechatUserIdAndContentType(userId, type))
            .thenReturn(Optional.of(pref));
        when(prefRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private List<BilibiliContent> candidates(
            ContentType type, int count, double baseRating) {
        List<BilibiliContent> list = new ArrayList<>();
        String prefix = switch (type) {
            case BANGUMI -> "anime";
            case SERIES -> "series";
            case MOVIE -> "movie";
            default -> "content";
        };
        for (int i = 0; i < count; i++) {
            BilibiliContent c = new BilibiliContent(type,
                prefix + "-" + (i + 1), "作品" + prefix + (i + 1));
            c.setRating(baseRating + (i * 0.1));
            c.setViewCount(1_000_000L - i * 50_000L);
            c.setGenres(new LinkedHashSet<>(Set.of("科幻", "热血")));
            list.add(c);
        }
        return list;
    }
}
