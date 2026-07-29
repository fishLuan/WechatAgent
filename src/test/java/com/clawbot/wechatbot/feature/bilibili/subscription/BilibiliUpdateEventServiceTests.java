package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.UpdateEventStatus;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliUpdateEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliUpdateEventServiceTests {
    private BilibiliUpdateEventRepository repository;
    private BilibiliSubscriptionRepository subscriptionRepository;
    private BilibiliUpdateEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(BilibiliUpdateEventRepository.class);
        subscriptionRepository = mock(BilibiliSubscriptionRepository.class);
        service = new BilibiliUpdateEventService(
            repository,
            subscriptionRepository,
            Clock.fixed(Instant.parse("2026-07-29T02:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsPendingEventForANewEpisode() {
        when(repository.existsBySubscriptionIdAndEpisodeId(
            "subscription-1", "ep-11")).thenReturn(false);
        when(repository.insert(any(BilibiliUpdateEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<BilibiliUpdateEvent> created =
            service.createPending(subscription(), latest());

        assertTrue(created.isPresent());
        assertEquals(UpdateEventStatus.PENDING, created.orElseThrow().getStatus());
        assertEquals(11, created.orElseThrow().getEpisodeNumber());
        assertEquals(
            Instant.parse("2026-07-29T02:00:00Z"),
            created.orElseThrow().getDetectedAt());
    }

    @Test
    void skipsAnEventThatAlreadyExists() {
        when(repository.existsBySubscriptionIdAndEpisodeId(
            "subscription-1", "ep-11")).thenReturn(true);

        assertFalse(service.createPending(subscription(), latest()).isPresent());
        verify(repository, never()).insert(any(BilibiliUpdateEvent.class));
    }

    @Test
    void treatsConcurrentDuplicateInsertAsIdempotentSuccess() {
        when(repository.existsBySubscriptionIdAndEpisodeId(
            "subscription-1", "ep-11")).thenReturn(false);
        when(repository.insert(any(BilibiliUpdateEvent.class)))
            .thenThrow(new DuplicateKeyException("duplicate"));

        assertFalse(service.createPending(subscription(), latest()).isPresent());
    }

    @Test
    void marksPendingEventAsNotified() {
        BilibiliUpdateEvent event =
            new BilibiliUpdateEvent("subscription-1", "user-1", "ep-11");
        event.setId("event-1");
        BilibiliSubscription subscription = subscription();
        when(repository.findById("event-1")).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);
        when(subscriptionRepository.findById("subscription-1"))
            .thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(subscription)).thenReturn(subscription);

        BilibiliUpdateEvent notified = service.markNotified("event-1");

        assertEquals(UpdateEventStatus.NOTIFIED, notified.getStatus());
        assertEquals(
            Instant.parse("2026-07-29T02:00:00Z"), notified.getNotifiedAt());
        assertEquals(
            Instant.parse("2026-07-29T02:00:00Z"),
            subscription.getLastNotifiedAt());
    }

    private BilibiliSubscription subscription() {
        BilibiliSubscription subscription = new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, "content-1", "season-1");
        subscription.setId("subscription-1");
        return subscription;
    }

    private BilibiliContent latest() {
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, "content-1", "测试番剧");
        content.setLatestEpisodeId("ep-11");
        content.setLatestEpisodeNumber(11);
        content.setLatestEpisodeTitle("第11话");
        content.setPageUrl("https://www.bilibili.com/bangumi/play/ep11");
        return content;
    }
}
