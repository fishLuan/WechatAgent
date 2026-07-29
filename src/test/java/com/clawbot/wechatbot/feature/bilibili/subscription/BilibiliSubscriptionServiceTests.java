package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.CheckResult;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliSubscriptionServiceTests {
    private BilibiliSubscriptionRepository repository;
    private BilibiliContentSource contentSource;
    private BilibiliSubscriptionCheckService checkService;
    private BilibiliSubscriptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(BilibiliSubscriptionRepository.class);
        contentSource = mock(BilibiliContentSource.class);
        checkService = mock(BilibiliSubscriptionCheckService.class);
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-29T01:00:00Z"), ZoneOffset.UTC);
        service = new DefaultBilibiliSubscriptionService(
            repository, contentSource, checkService, clock);
    }

    @Test
    void recordsCurrentEpisodeAsBaselineWhenSubscribing() throws Exception {
        BilibiliContent content = trackableContent();
        when(contentSource.findByContentId(ContentType.BANGUMI, "content-1"))
            .thenReturn(Optional.of(content));
        when(contentSource.refresh(content)).thenReturn(content);
        when(repository.findByWechatUserIdAndSeasonId("user-1", "season-1"))
            .thenReturn(Optional.empty());
        when(repository.insert(any(BilibiliSubscription.class)))
            .thenAnswer(invocation -> {
                BilibiliSubscription value = invocation.getArgument(0);
                value.setId("subscription-1");
                return value;
            });

        SubscriptionResult result = service.subscribeByContentId(
            "user-1", ContentType.BANGUMI, "content-1");

        assertTrue(result.success());
        assertFalse(result.alreadySubscribed());
        assertEquals(SubscriptionStatus.ACTIVE, result.status());
        assertEquals(10, result.latestEpisodeNumber());
    }

    @Test
    void repeatedSubscriptionReactivatesExistingRecordWithoutInsert() throws Exception {
        BilibiliSubscription existing = new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, "content-1", "season-1");
        existing.setId("subscription-1");
        existing.setStatus(SubscriptionStatus.PAUSED);
        when(repository.findByWechatUserIdAndSeasonId("user-1", "season-1"))
            .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        BilibiliContent content = trackableContent();
        when(contentSource.findByContentId(ContentType.BANGUMI, "content-1"))
            .thenReturn(Optional.of(content));
        when(contentSource.refresh(content)).thenReturn(content);

        SubscriptionResult result = service.subscribeByContentId(
            "user-1", ContentType.BANGUMI, "content-1");

        assertTrue(result.success());
        assertTrue(result.alreadySubscribed());
        assertEquals(SubscriptionStatus.ACTIVE, result.status());
        verify(repository, never()).insert(any(BilibiliSubscription.class));
    }

    @Test
    void rejectsMovieSubscription() throws Exception {
        BilibiliContent movie =
            new BilibiliContent(ContentType.MOVIE, "movie-1", "测试电影");
        movie.setSeasonId("movie-season");
        when(contentSource.findByContentId(ContentType.MOVIE, "movie-1"))
            .thenReturn(Optional.of(movie));
        when(contentSource.refresh(movie)).thenReturn(movie);

        SubscriptionResult result = service.subscribeByContentId(
            "user-1", ContentType.MOVIE, "movie-1");

        assertFalse(result.success());
        assertTrue(result.message().contains("想看"));
        verify(repository, never()).insert(any(BilibiliSubscription.class));
    }

    @Test
    void userCannotPauseAnotherUsersSubscription() {
        BilibiliSubscription other = new BilibiliSubscription(
            "user-2", ContentType.SERIES, "series-1", "season-2");
        other.setId("subscription-2");
        when(repository.findById("subscription-2")).thenReturn(Optional.of(other));

        OperationResult result = service.pause("user-1", "subscription-2");

        assertFalse(result.success());
        verify(repository, never()).save(other);
    }

    @Test
    void listsOnlyCurrentUsersActiveAndPausedSubscriptions() {
        BilibiliSubscription active = new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, "a", "season-a");
        BilibiliSubscription paused = new BilibiliSubscription(
            "user-1", ContentType.SERIES, "b", "season-b");
        paused.setStatus(SubscriptionStatus.PAUSED);
        when(repository.findByWechatUserIdAndStatus(
            "user-1", SubscriptionStatus.ACTIVE)).thenReturn(List.of(active));
        when(repository.findByWechatUserIdAndStatus(
            "user-1", SubscriptionStatus.PAUSED)).thenReturn(List.of(paused));

        assertEquals(2, service.listSubscriptions("user-1").size());
    }

    @Test
    void checksOnlyTheCurrentUsersActiveSubscriptions() throws Exception {
        BilibiliSubscription subscription = new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, "content-1", "season-1");
        subscription.setId("subscription-1");
        EpisodeUpdateNotification notification = new EpisodeUpdateNotification(
            "subscription-1",
            "测试番剧",
            "ep-11",
            11,
            "第11话",
            "https://www.bilibili.com/ep11",
            Instant.parse("2026-07-29T01:00:00Z"));
        when(repository.findByWechatUserIdAndStatus(
            "user-1", SubscriptionStatus.ACTIVE))
            .thenReturn(List.of(subscription));
        when(checkService.check(subscription)).thenReturn(
            new BilibiliSubscriptionCheckService.CheckOutcome(
                Optional.of(notification)));

        CheckResult result = service.checkNow("user-1");

        assertEquals(1, result.checkedCount());
        assertEquals(1, result.updateCount());
        assertEquals(notification, result.updates().get(0));
    }

    private BilibiliContent trackableContent() {
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, "content-1", "测试番剧");
        content.setSeasonId("season-1");
        content.setLatestEpisodeId("ep-10");
        content.setLatestEpisodeNumber(10);
        return content;
    }
}
