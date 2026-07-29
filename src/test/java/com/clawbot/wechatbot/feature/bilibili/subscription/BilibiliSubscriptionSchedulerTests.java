package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliSubscriptionSchedulerTests {
    private BilibiliSubscriptionRepository repository;
    private BilibiliContentSource contentSource;
    private BilibiliUpdateEventService eventService;
    private BilibiliSubscriptionScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(BilibiliSubscriptionRepository.class);
        contentSource = mock(BilibiliContentSource.class);
        eventService = mock(BilibiliUpdateEventService.class);
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-29T03:00:00Z"), ZoneOffset.UTC);
        BilibiliSubscriptionCheckService checkService =
            new BilibiliSubscriptionCheckService(
                repository,
                contentSource,
                new BilibiliUpdateDetector(),
                eventService,
                clock);
        scheduler = new BilibiliSubscriptionScheduler(
            repository, checkService);
    }

    @Test
    void createsOneEventAndAdvancesSubscriptionBaseline() throws Exception {
        BilibiliSubscription subscription = subscription("sub-1", "content-1", 10);
        BilibiliContent latest = content("content-1", 11);
        when(repository.findByStatus(SubscriptionStatus.ACTIVE))
            .thenReturn(List.of(subscription));
        latest.setSeasonId("season-sub-1");
        when(contentSource.findBySeasonId(
            ContentType.BANGUMI, "season-sub-1"))
            .thenReturn(Optional.of(latest));
        when(eventService.createPending(subscription, latest))
            .thenReturn(Optional.of(new BilibiliUpdateEvent(
                "sub-1", "user-1", "ep-11")));
        when(repository.save(subscription)).thenReturn(subscription);

        BilibiliSubscriptionScheduler.CheckSummary summary = scheduler.checkNow();

        assertEquals(1, summary.checked());
        assertEquals(1, summary.eventsCreated());
        assertEquals(0, summary.failures());
        assertEquals(11, subscription.getLastKnownEpisodeNumber());
        assertNotNull(subscription.getLastCheckedAt());
    }

    @Test
    void firstCheckWithoutBaselineDoesNotCreateOldEpisodeEvent() throws Exception {
        BilibiliSubscription subscription = subscription("sub-1", "content-1", null);
        BilibiliContent latest = content("content-1", 10);
        when(repository.findByStatus(SubscriptionStatus.ACTIVE))
            .thenReturn(List.of(subscription));
        latest.setSeasonId("season-sub-1");
        when(contentSource.findBySeasonId(
            ContentType.BANGUMI, "season-sub-1"))
            .thenReturn(Optional.of(latest));
        when(repository.save(subscription)).thenReturn(subscription);

        BilibiliSubscriptionScheduler.CheckSummary summary = scheduler.checkNow();

        assertEquals(0, summary.eventsCreated());
        assertEquals(10, subscription.getLastKnownEpisodeNumber());
        verify(eventService, never()).createPending(any(), any());
    }

    @Test
    void oneFailedSubscriptionDoesNotBlockTheRest() throws Exception {
        BilibiliSubscription broken = subscription("sub-1", "broken", 10);
        BilibiliSubscription healthy = subscription("sub-2", "healthy", 10);
        BilibiliContent latest = content("healthy", 11);
        latest.setSeasonId("season-sub-2");
        when(repository.findByStatus(SubscriptionStatus.ACTIVE))
            .thenReturn(List.of(broken, healthy));
        when(contentSource.findBySeasonId(
            ContentType.BANGUMI, "season-sub-1"))
            .thenThrow(new IllegalStateException("模拟接口失败"));
        when(contentSource.findBySeasonId(
            ContentType.BANGUMI, "season-sub-2"))
            .thenReturn(Optional.of(latest));
        when(eventService.createPending(healthy, latest))
            .thenReturn(Optional.of(new BilibiliUpdateEvent(
                "sub-2", "user-1", "ep-11")));

        BilibiliSubscriptionScheduler.CheckSummary summary = scheduler.checkNow();

        assertEquals(2, summary.scheduled());
        assertEquals(1, summary.checked());
        assertEquals(1, summary.failures());
        assertEquals(1, summary.eventsCreated());
        verify(repository).save(healthy);
    }

    @Test
    void repairsLegacyContentIdUsingCanonicalSeasonSnapshot()
        throws Exception {
        BilibiliSubscription subscription =
            subscription("sub-1", "legacy-media-id", 10);
        BilibiliContent latest = content("canonical-media-id", 11);
        latest.setSeasonId("season-sub-1");
        when(repository.findByStatus(SubscriptionStatus.ACTIVE))
            .thenReturn(List.of(subscription));
        when(contentSource.findBySeasonId(
            ContentType.BANGUMI, "season-sub-1"))
            .thenReturn(Optional.of(latest));
        when(eventService.createPending(subscription, latest))
            .thenReturn(Optional.of(new BilibiliUpdateEvent(
                "sub-1", "user-1", "ep-11")));

        BilibiliSubscriptionScheduler.CheckSummary summary =
            scheduler.checkNow();

        assertEquals(0, summary.failures());
        assertEquals("canonical-media-id", subscription.getContentId());
        verify(repository).save(subscription);
    }

    @Test
    void fallsBackToMediaIdForLegacySubscriptionWithoutSeasonId()
        throws Exception {
        BilibiliSubscription subscription =
            subscription("sub-1", "media-1", 10);
        subscription.setSeasonId(null);
        BilibiliContent stored = content("media-1", 11);
        stored.setSeasonId("season-sub-1");
        when(repository.findByStatus(SubscriptionStatus.ACTIVE))
            .thenReturn(List.of(subscription));
        when(contentSource.findByContentId(
            ContentType.BANGUMI, "media-1"))
            .thenReturn(Optional.of(stored));
        when(contentSource.refresh(stored)).thenReturn(stored);
        when(eventService.createPending(subscription, stored))
            .thenReturn(Optional.of(new BilibiliUpdateEvent(
                "sub-1", "user-1", "ep-11")));

        BilibiliSubscriptionScheduler.CheckSummary summary =
            scheduler.checkNow();

        assertEquals(0, summary.failures());
        assertEquals("season-sub-1", subscription.getSeasonId());
    }

    private BilibiliSubscription subscription(
        String id, String contentId, Integer episodeNumber
    ) {
        BilibiliSubscription subscription = new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, contentId, "season-" + id);
        subscription.setId(id);
        subscription.setLastKnownEpisodeNumber(episodeNumber);
        if (episodeNumber != null) {
            subscription.setLastKnownEpisodeId("ep-" + episodeNumber);
        }
        return subscription;
    }

    private BilibiliContent content(String contentId, int episodeNumber) {
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, contentId, "测试番剧");
        content.setLatestEpisodeNumber(episodeNumber);
        content.setLatestEpisodeId("ep-" + episodeNumber);
        return content;
    }
}
