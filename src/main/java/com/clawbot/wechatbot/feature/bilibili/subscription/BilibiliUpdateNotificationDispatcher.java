package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliNotificationPort;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/** 将待投递的追更事件发送给用户，并回写投递状态。 */
public final class BilibiliUpdateNotificationDispatcher {
    private static final Logger log =
        LoggerFactory.getLogger(BilibiliUpdateNotificationDispatcher.class);

    private final BilibiliUpdateEventService eventService;
    private final BilibiliSubscriptionRepository subscriptionRepository;
    private final BilibiliNotificationPort notificationPort;

    public BilibiliUpdateNotificationDispatcher(
        BilibiliUpdateEventService eventService,
        BilibiliSubscriptionRepository subscriptionRepository,
        BilibiliNotificationPort notificationPort
    ) {
        this.eventService = eventService;
        this.subscriptionRepository = subscriptionRepository;
        this.notificationPort = notificationPort;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    public DispatchSummary dispatchPendingEvents() {
        List<BilibiliUpdateEvent> events = eventService.pendingEvents();
        int notified = 0;
        int failed = 0;
        for (BilibiliUpdateEvent event : events) {
            try {
                notificationPort.notifyEpisodeUpdate(
                    event.getWechatUserId(), toNotification(event));
                eventService.markNotified(event.getId());
                notified++;
            } catch (Exception error) {
                failed++;
                String reason = safeMessage(error);
                eventService.markFailed(event.getId(), reason);
                log.warn(
                    "B 站追更通知投递失败 eventId={} userId={} reason={}",
                    event.getId(), event.getWechatUserId(), reason);
            }
        }
        if (!events.isEmpty()) {
            log.info(
                "B 站追更通知投递完成：待投递 {} 条，成功 {} 条，失败 {} 条",
                events.size(), notified, failed);
        }
        return new DispatchSummary(events.size(), notified, failed);
    }

    private EpisodeUpdateNotification toNotification(BilibiliUpdateEvent event) {
        String title = subscriptionRepository.findById(event.getSubscriptionId())
            .map(BilibiliSubscription::getTitle)
            .filter(value -> !value.isBlank())
            .orElse("B站追更作品");
        return new EpisodeUpdateNotification(
            event.getSubscriptionId(),
            title,
            event.getEpisodeId(),
            event.getEpisodeNumber(),
            event.getEpisodeTitle(),
            event.getEpisodeUrl(),
            event.getDetectedAt());
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName()
            : error.getMessage();
    }

    public record DispatchSummary(int pending, int notified, int failed) {
    }
}
