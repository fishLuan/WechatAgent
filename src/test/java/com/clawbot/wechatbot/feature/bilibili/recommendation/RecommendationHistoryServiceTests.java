package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliRecommendationHistory;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationState;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationHistoryServiceTests {

    @Mock
    private BilibiliRecommendationHistoryRepository repository;
    private RecommendationHistoryService service;
    private final String userId = "wechat-user-1";

    @BeforeEach
    void setUp() {
        service = new RecommendationHistoryService(repository);
    }

    @Test
    void recordsNewRecommendation() {
        when(repository.findByWechatUserIdAndContentTypeAndContentId(
                userId, ContentType.BANGUMI, "anime-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recordRecommendation(userId, ContentType.BANGUMI, "anime-1", "番剧A");

        verify(repository, times(1)).save(any());
    }

    @Test
    void recordsAndExcludesDislikedContent() {
        BilibiliRecommendationHistory history =
            new BilibiliRecommendationHistory(userId, ContentType.BANGUMI, "bad-anime");
        history.setState(RecommendationState.DISLIKED);

        when(repository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of(history));

        List<String> excluded = service.findExcludedContentIds(userId, ContentType.BANGUMI);

        assertTrue(excluded.contains("bad-anime"));
    }

    @Test
    void animeAndMovieExclusionsAreIndependent() {
        when(repository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of(
                createHistory(userId, ContentType.BANGUMI, "bad-anime", RecommendationState.DISLIKED)));
        when(repository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.MOVIE), anyCollection()))
            .thenReturn(List.of(
                createHistory(userId, ContentType.MOVIE, "bad-movie", RecommendationState.DISLIKED)));

        List<String> animeExcluded = service.findExcludedContentIds(userId, ContentType.BANGUMI);
        List<String> movieExcluded = service.findExcludedContentIds(userId, ContentType.MOVIE);

        assertTrue(animeExcluded.contains("bad-anime"));
        assertFalse(animeExcluded.contains("bad-movie"));
        assertTrue(movieExcluded.contains("bad-movie"));
        assertFalse(movieExcluded.contains("bad-anime"));
    }

    @Test
    void differentUsersAreIsolated() {
        when(repository.findByWechatUserIdAndContentTypeAndStateIn(
                eq("user-a"), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of(
                createHistory("user-a", ContentType.BANGUMI, "anime-1", RecommendationState.DISLIKED)));
        when(repository.findByWechatUserIdAndContentTypeAndStateIn(
                eq("user-b"), eq(ContentType.BANGUMI), anyCollection()))
            .thenReturn(List.of(
                createHistory("user-b", ContentType.BANGUMI, "anime-2", RecommendationState.DISLIKED)));

        List<String> userAExcluded = service.findExcludedContentIds("user-a", ContentType.BANGUMI);
        List<String> userBExcluded = service.findExcludedContentIds("user-b", ContentType.BANGUMI);

        assertTrue(userAExcluded.contains("anime-1"));
        assertFalse(userAExcluded.contains("anime-2"));
        assertTrue(userBExcluded.contains("anime-2"));
    }

    @Test
    void wantToWatchIsAlsoExcluded() {
        when(repository.findByWechatUserIdAndContentTypeAndStateIn(
                eq(userId), eq(ContentType.MOVIE), anyCollection()))
            .thenReturn(List.of(
                createHistory(userId, ContentType.MOVIE, "movie-1", RecommendationState.WANT_TO_WATCH)));

        List<String> excluded = service.findExcludedContentIds(userId, ContentType.MOVIE);

        assertTrue(excluded.contains("movie-1"));
    }

    @Test
    void markWatchedUpdatesState() {
        BilibiliRecommendationHistory history =
            new BilibiliRecommendationHistory(userId, ContentType.BANGUMI, "anime-1");
        when(repository.findByWechatUserIdAndContentTypeAndContentId(
                userId, ContentType.BANGUMI, "anime-1"))
            .thenReturn(Optional.of(history));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.markWatched(userId, ContentType.BANGUMI, "anime-1");

        assertEquals(RecommendationState.WATCHED, history.getState());
    }

    @Test
    void contentNotInHistoryCanStillBeMarked() {
        when(repository.findByWechatUserIdAndContentTypeAndContentId(
                userId, ContentType.MOVIE, "movie-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.markWatched(userId, ContentType.MOVIE, "movie-1");

        verify(repository, times(1)).save(argThat(h ->
            h.getWechatUserId().equals(userId)
                && h.getContentType() == ContentType.MOVIE
                && h.getContentId().equals("movie-1")
                && h.getState() == RecommendationState.WATCHED));
    }

    @Test
    void titleIsStoredWhenMarkingContentByName() {
        when(repository.findByWechatUserIdAndContentTypeAndContentId(
                userId, ContentType.MOVIE, "movie-red"))
            .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.markWatched(
            userId,
            ContentType.MOVIE,
            "movie-red",
            "航海王：红发歌姬");

        verify(repository).save(argThat(history ->
            "航海王：红发歌姬".equals(history.getTitle())
                && history.getState() == RecommendationState.WATCHED));
    }

    private static BilibiliRecommendationHistory createHistory(
            String wechatUserId, ContentType contentType,
            String contentId, RecommendationState state) {
        BilibiliRecommendationHistory h =
            new BilibiliRecommendationHistory(wechatUserId, contentType, contentId);
        h.setState(state);
        return h;
    }
}
