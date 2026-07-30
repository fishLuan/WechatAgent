package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliUpdateEventService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消费角色四生成的更新事件并主动发送微信提醒。
 *
 * <p>发送成功后标记 NOTIFIED；暂时不可发送时保留 PENDING；
 * 其他失败按配置重试，超过次数后标记 FAILED。</p>
 */
@Component
@ConditionalOnProperty(
    name = "clawbot.bilibili.enabled",
    havingValue = "true"
)
public final class BilibiliUpdateNotificationScheduler {
    private final BilibiliUpdateEventService events;
    private final BilibiliSubscriptionService subscriptions;
    private final BilibiliNotificationPort notifications;
    private final WeChatSessionRegistry sessions;
    private final WeChatOutboundGateway gateway;
    private final BilibiliProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public BilibiliUpdateNotificationScheduler(
        BilibiliUpdateEventService events,
        BilibiliSubscriptionService subscriptions,
        BilibiliNotificationPort notifications,
        WeChatSessionRegistry sessions,
        WeChatOutboundGateway gateway,
        BilibiliProperties properties
    ) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.sessions = sessions;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void deliverPendingEvents() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (BilibiliUpdateEvent event : events.pendingEvents()) {
                deliver(event);
            }
        } finally {
            running.set(false);
        }
    }

    private void deliver(BilibiliUpdateEvent event) {
        if (event == null || event.getId() == null) return;
        if (event.getNextAttemptAt() != null
            && event.getNextAttemptAt().isAfter(Instant.now())) {
            return;
        }
        String userId = event.getWechatUserId();
        if (!gateway.isAvailable(userId)) {
            // 会话恢复后继续发送，不把临时离线计入失败次数。
            return;
        }
        try {
            String title = resolveTitle(event);
            notifications.notifyEpisodeUpdate(
                userId,
                new EpisodeUpdateNotification(
                    event.getSubscriptionId(),
                    title,
                    event.getEpisodeId(),
                    event.getEpisodeNumber(),
                    event.getEpisodeTitle(),
                    event.getEpisodeUrl(),
                    event.getDetectedAt()));
            events.markNotified(event.getId());
            System.out.println(
                "[BILIBILI] 更新提醒已发送 eventId=" + event.getId());
        } catch (Exception error) {
            events.recordDeliveryFailure(
                event.getId(),
                error.getMessage(),
                properties.getMaxRetries() + 1,
                Duration.ofMinutes(2));
            System.err.println(
                "[BILIBILI] 更新提醒发送失败 eventId=" + event.getId()
                    + "：" + error.getMessage());
        }
    }

    private String resolveTitle(BilibiliUpdateEvent event) {
        List<SubscriptionView> userSubscriptions =
            subscriptions.listSubscriptions(event.getWechatUserId());
        return userSubscriptions.stream()
            .filter(item -> event.getSubscriptionId().equals(item.subscriptionId()))
            .map(SubscriptionView::title)
            .filter(title -> title != null && !title.isBlank())
            .findFirst()
            .orElse("你订阅的作品");
    }
}
