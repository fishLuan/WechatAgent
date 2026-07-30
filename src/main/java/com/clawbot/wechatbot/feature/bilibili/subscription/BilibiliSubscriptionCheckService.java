package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Optional;

/** 定时检查和用户主动检查共用的单订阅检查流程。 */
public final class BilibiliSubscriptionCheckService {
    private final BilibiliSubscriptionRepository repository;
    private final BilibiliContentSource contentSource;
    private final BilibiliUpdateDetector detector;
    private final BilibiliUpdateEventService eventService;
    private final Clock clock;

    public BilibiliSubscriptionCheckService(
        BilibiliSubscriptionRepository repository,
        BilibiliContentSource contentSource,
        BilibiliUpdateDetector detector,
        BilibiliUpdateEventService eventService,
        Clock clock
    ) {
        this.repository = repository;
        this.contentSource = contentSource;
        this.detector = detector;
        this.eventService = eventService;
        this.clock = clock;
    }

    public CheckOutcome check(BilibiliSubscription subscription) throws Exception {
        String lookupId = subscription.getSeasonId() != null && !subscription.getSeasonId().isBlank()
            ? subscription.getSeasonId()
            : subscription.getContentId();
        BilibiliContent stored = contentSource.findByContentId(
                subscription.getContentType(), lookupId)
            .orElseThrow(() -> new NoSuchElementException(
                "未找到订阅作品：" + subscription.getContentId()));
        BilibiliContent latest = contentSource.refresh(stored);
        boolean hadBaseline = detector.hasBaseline(subscription);
        boolean newEpisode = detector.hasNewEpisode(subscription, latest);
        Optional<BilibiliUpdateEvent> event = newEpisode
            ? eventService.createPending(subscription, latest)
            : Optional.empty();

        if (!hadBaseline || newEpisode) {
            subscription.setLastKnownEpisodeId(latest.getLatestEpisodeId());
            subscription.setLastKnownEpisodeNumber(latest.getLatestEpisodeNumber());
        }
        subscription.setLastCheckedAt(clock.instant());
        subscription.setUpdatedAt(clock.instant());
        repository.save(subscription);
        return new CheckOutcome(event.map(value -> notification(
            subscription, latest, value)));
    }

    private EpisodeUpdateNotification notification(
        BilibiliSubscription subscription,
        BilibiliContent latest,
        BilibiliUpdateEvent event
    ) {
        String title = subscription.getTitle();
        if (title == null || title.isBlank()) title = latest.getTitle();
        return new EpisodeUpdateNotification(
            subscription.getId(),
            title,
            event.getEpisodeId(),
            event.getEpisodeNumber(),
            event.getEpisodeTitle(),
            event.getEpisodeUrl(),
            event.getDetectedAt());
    }

    public record CheckOutcome(
        Optional<EpisodeUpdateNotification> notification
    ) {
        public CheckOutcome {
            notification = notification == null ? Optional.empty() : notification;
        }

        public boolean eventCreated() {
            return notification.isPresent();
        }
    }
}