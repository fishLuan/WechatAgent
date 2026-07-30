package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliNotificationPort;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliUpdateNotificationDispatcherTests {
    private BilibiliUpdateEventService eventService;
    private BilibiliSubscriptionRepository subscriptionRepository;
    private BilibiliNotificationPort notificationPort;
    private BilibiliUpdateNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        eventService = mock(BilibiliUpdateEventService.class);
        subscriptionRepository = mock(BilibiliSubscriptionRepository.class);
        notificationPort = mock(BilibiliNotificationPort.class);
        dispatcher = new BilibiliUpdateNotificationDispatcher(
            eventService, subscriptionRepository, notificationPort);
    }

    @Test
    void sendsPendingEventAndMarksItNotified() {
        BilibiliUpdateEvent event = event();
        when(eventService.pendingEvents()).thenReturn(List.of(event));
        when(subscriptionRepository.findById("subscription-1"))
            .thenReturn(Optional.of(subscription()));

        BilibiliUpdateNotificationDispatcher.DispatchSummary summary =
            dispatcher.dispatchPendingEvents();

        assertEquals(1, summary.pending());
        assertEquals(1, summary.notified());
        assertEquals(0, summary.failed());
        verify(notificationPort).notifyEpisodeUpdate(
            eq("user-1"), any(EpisodeUpdateNotification.class));
        verify(eventService).markNotified("event-1");
    }

    @Test
    void marksEventFailedWhenNotificationFails() {
        BilibiliUpdateEvent event = event();
        when(eventService.pendingEvents()).thenReturn(List.of(event));
        when(subscriptionRepository.findById("subscription-1"))
            .thenReturn(Optional.of(subscription()));
        doThrow(new IllegalStateException("微信发送通道不可用"))
            .when(notificationPort)
            .notifyEpisodeUpdate(eq("user-1"), any(EpisodeUpdateNotification.class));

        BilibiliUpdateNotificationDispatcher.DispatchSummary summary =
            dispatcher.dispatchPendingEvents();

        assertEquals(1, summary.pending());
        assertEquals(0, summary.notified());
        assertEquals(1, summary.failed());
        verify(eventService).markFailed("event-1", "微信发送通道不可用");
    }

    private BilibiliUpdateEvent event() {
        BilibiliUpdateEvent event =
            new BilibiliUpdateEvent("subscription-1", "user-1", "ep-11");
        event.setId("event-1");
        event.setEpisodeNumber(11);
        event.setEpisodeTitle("第11话");
        event.setEpisodeUrl("https://www.bilibili.com/bangumi/play/ep11");
        event.setDetectedAt(Instant.parse("2026-07-30T02:00:00Z"));
        return event;
    }

    private BilibiliSubscription subscription() {
        BilibiliSubscription subscription = new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, "content-1", "season-1");
        subscription.setId("subscription-1");
        subscription.setTitle("测试番剧");
        return subscription;
    }
}
