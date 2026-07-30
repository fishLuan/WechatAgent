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
        if (subscription == null) {
            throw new IllegalArgumentException("订阅不能为空");
        }
        BilibiliContent latest = loadLatest(subscription);
        reconcileIdentity(subscription, latest);
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

    private BilibiliContent loadLatest(
        BilibiliSubscription subscription
    ) throws Exception {
        String seasonId = normalized(subscription.getSeasonId());
        if (seasonId != null) {
            Optional<BilibiliContent> bySeason =
                contentSource.findBySeasonId(
                    subscription.getContentType(), seasonId);
            if (bySeason.isPresent()) return bySeason.get();
        }

        String contentId = normalized(subscription.getContentId());
        if (contentId != null) {
            Optional<BilibiliContent> byContent =
                contentSource.findByContentId(
                    subscription.getContentType(), contentId);
            if (byContent.isPresent()) {
                return contentSource.refresh(byContent.get());
            }
        }
        throw new NoSuchElementException(
            "未找到订阅作品：seasonId=" + safeValue(seasonId)
                + ", contentId=" + safeValue(contentId));
    }

    private void reconcileIdentity(
        BilibiliSubscription subscription,
        BilibiliContent latest
    ) {
        if (latest == null
            || latest.getContentType() != subscription.getContentType()) {
            throw new IllegalArgumentException(
                "最新作品快照与订阅内容类型不一致");
        }
        if (!compatibleTitle(subscription.getTitle(), latest.getTitle())) {
            throw new IllegalArgumentException(
                "订阅季度对应的作品标题不一致，拒绝自动修复");
        }

        String latestContentId = normalized(latest.getContentId());
        String latestSeasonId = normalized(latest.getSeasonId());
        if (latestContentId == null || latestSeasonId == null) {
            throw new IllegalArgumentException(
                "最新作品快照缺少 contentId 或 seasonId");
        }

        String oldContentId = normalized(subscription.getContentId());
        String oldSeasonId = normalized(subscription.getSeasonId());
        if (!latestContentId.equals(oldContentId)
            || !latestSeasonId.equals(oldSeasonId)) {
            System.out.println(
                "[BILIBILI] 自动修复订阅作品标识 subscriptionId="
                    + safeValue(subscription.getId())
                    + " contentId=" + safeValue(oldContentId)
                    + "->" + latestContentId
                    + " seasonId=" + safeValue(oldSeasonId)
                    + "->" + latestSeasonId);
            subscription.setContentId(latestContentId);
            subscription.setSeasonId(latestSeasonId);
        }
        if (latest.getTitle() != null && !latest.getTitle().isBlank()) {
            subscription.setTitle(latest.getTitle().trim());
        }
    }

    private boolean compatibleTitle(String existing, String latest) {
        String currentTitle = normalizedTitle(existing);
        String latestTitle = normalizedTitle(latest);
        return currentTitle == null
            || latestTitle == null
            || currentTitle.equals(latestTitle)
            || currentTitle.contains(latestTitle)
            || latestTitle.contains(currentTitle);
    }

    private String normalizedTitle(String value) {
        String normalized = normalized(value);
        if (normalized == null) return null;
        return normalized.toLowerCase()
            .replace("的", "")
            .replaceAll("[\\s·・:：\\-—_《》【】（）()]+", "");
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeValue(String value) {
        return value == null ? "<empty>" : value;
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
