package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliUpdateEventService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliUpdateNotificationSchedulerTests {

    @Test
    void sendsPendingEventAndMarksItNotified() {
        BilibiliUpdateEventService events = mock(BilibiliUpdateEventService.class);
        BilibiliSubscriptionService subscriptions =
            mock(BilibiliSubscriptionService.class);
        BilibiliNotificationPort notifications = mock(BilibiliNotificationPort.class);
        WeChatSessionRegistry sessions = new WeChatSessionRegistry();
        WeChatOutboundGateway gateway = mock(WeChatOutboundGateway.class);
        BilibiliUpdateEvent event = event();
        when(events.pendingEvents()).thenReturn(List.of(event));
        when(subscriptions.listSubscriptions("user-1")).thenReturn(List.of(
            new SubscriptionView(
                "sub-1", ContentType.BANGUMI, "media-1", "season-1",
                "测试动漫", SubscriptionStatus.ACTIVE, 7)));
        when(gateway.isAvailable("user-1")).thenReturn(true);
        sessions.markActive("user-1");

        scheduler(events, subscriptions, notifications, sessions, gateway)
            .deliverPendingEvents();

        verify(notifications).notifyEpisodeUpdate(
            org.mockito.ArgumentMatchers.eq("user-1"), any());
        verify(events).markNotified("event-1");
    }

    @Test
    void keepsEventPendingWhileWechatSessionIsUnavailable() {
        BilibiliUpdateEventService events = mock(BilibiliUpdateEventService.class);
        BilibiliSubscriptionService subscriptions =
            mock(BilibiliSubscriptionService.class);
        BilibiliNotificationPort notifications = mock(BilibiliNotificationPort.class);
        WeChatSessionRegistry sessions = new WeChatSessionRegistry();
        WeChatOutboundGateway gateway = mock(WeChatOutboundGateway.class);
        when(events.pendingEvents()).thenReturn(List.of(event()));
        when(gateway.isAvailable("user-1")).thenReturn(false);

        scheduler(events, subscriptions, notifications, sessions, gateway)
            .deliverPendingEvents();

        verify(notifications, never()).notifyEpisodeUpdate(any(), any());
        verify(events, never()).markNotified(any());
        verify(events, never()).recordDeliveryFailure(
            any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private BilibiliUpdateNotificationScheduler scheduler(
        BilibiliUpdateEventService events,
        BilibiliSubscriptionService subscriptions,
        BilibiliNotificationPort notifications,
        WeChatSessionRegistry sessions,
        WeChatOutboundGateway gateway
    ) {
        BilibiliProperties properties = new BilibiliProperties();
        properties.setMaxRetries(2);
        return new BilibiliUpdateNotificationScheduler(
            events, subscriptions, notifications, sessions, gateway, properties);
    }

    private BilibiliUpdateEvent event() {
        BilibiliUpdateEvent event =
            new BilibiliUpdateEvent("sub-1", "user-1", "episode-8");
        event.setId("event-1");
        event.setEpisodeNumber(8);
        event.setEpisodeUrl("https://www.bilibili.com/bangumi/play/ep8");
        return event;
    }
}
