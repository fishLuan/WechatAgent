package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.UpdateEventStatus;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliUpdateEventRepository;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** 创建幂等更新事件，并维护后续微信投递状态。 */
public final class BilibiliUpdateEventService {
    private final BilibiliUpdateEventRepository repository;
    private final BilibiliSubscriptionRepository subscriptionRepository;
    private final Clock clock;

    public BilibiliUpdateEventService(
        BilibiliUpdateEventRepository repository,
        BilibiliSubscriptionRepository subscriptionRepository,
        Clock clock
    ) {
        this.repository = repository;
        this.subscriptionRepository = subscriptionRepository;
        this.clock = clock;
    }

    public Optional<BilibiliUpdateEvent> createPending(
        BilibiliSubscription subscription, BilibiliContent latest
    ) {
        if (subscription == null || subscription.getId() == null
            || subscription.getId().isBlank()) {
            throw new IllegalArgumentException("订阅必须先保存后才能生成更新事件");
        }
        String episodeId = eventEpisodeId(latest);
        if (repository.existsBySubscriptionIdAndEpisodeId(
            subscription.getId(), episodeId)) {
            return Optional.empty();
        }

        BilibiliUpdateEvent event = new BilibiliUpdateEvent(
            subscription.getId(), subscription.getWechatUserId(), episodeId);
        event.setEpisodeNumber(latest.getLatestEpisodeNumber());
        event.setEpisodeTitle(latest.getLatestEpisodeTitle());
        event.setEpisodeUrl(latest.getPageUrl());
        event.setDetectedAt(clock.instant());
        event.setUpdatedAt(clock.instant());
        try {
            return Optional.of(repository.insert(event));
        } catch (DuplicateKeyException duplicate) {
            // exists + insert 之间仍可能并发，唯一索引是最终的幂等保障。
            return Optional.empty();
        }
    }

    public List<BilibiliUpdateEvent> pendingEvents() {
        return repository.findByStatusOrderByDetectedAtAsc(UpdateEventStatus.PENDING);
    }

    public BilibiliUpdateEvent markNotified(String eventId) {
        BilibiliUpdateEvent event = requiredEvent(eventId);
        event.setStatus(UpdateEventStatus.NOTIFIED);
        event.setFailureReason(null);
        event.setNextAttemptAt(null);
        event.setNotifiedAt(clock.instant());
        event.setUpdatedAt(clock.instant());
        BilibiliUpdateEvent saved = repository.save(event);
        subscriptionRepository.findById(event.getSubscriptionId())
            .ifPresent(subscription -> {
                subscription.setLastNotifiedAt(clock.instant());
                subscription.setUpdatedAt(clock.instant());
                subscriptionRepository.save(subscription);
            });
        return saved;
    }

    public BilibiliUpdateEvent markFailed(String eventId, String reason) {
        BilibiliUpdateEvent event = requiredEvent(eventId);
        event.setStatus(UpdateEventStatus.FAILED);
        event.setFailureReason(
            reason == null || reason.isBlank() ? "未知投递错误" : reason.trim());
        event.setUpdatedAt(clock.instant());
        return repository.save(event);
    }

    /**
     * 记录一次投递失败。达到最大次数前仍保持 PENDING，供角色五稍后重试。
     */
    public BilibiliUpdateEvent recordDeliveryFailure(
        String eventId,
        String reason,
        int maxAttempts,
        Duration retryDelay
    ) {
        BilibiliUpdateEvent event = requiredEvent(eventId);
        int attempts = event.getDeliveryAttempts() + 1;
        event.setDeliveryAttempts(attempts);
        event.setFailureReason(
            reason == null || reason.isBlank() ? "未知投递错误" : reason.trim());
        if (attempts >= Math.max(1, maxAttempts)) {
            event.setStatus(UpdateEventStatus.FAILED);
            event.setNextAttemptAt(null);
        } else {
            event.setStatus(UpdateEventStatus.PENDING);
            Duration delay = retryDelay == null || retryDelay.isNegative()
                ? Duration.ofMinutes(1) : retryDelay;
            event.setNextAttemptAt(clock.instant().plus(delay));
        }
        event.setUpdatedAt(clock.instant());
        return repository.save(event);
    }

    private BilibiliUpdateEvent requiredEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        return repository.findById(eventId.trim())
            .orElseThrow(() -> new NoSuchElementException("更新事件不存在"));
    }

    private String eventEpisodeId(BilibiliContent latest) {
        if (latest == null) throw new IllegalArgumentException("最新作品快照不能为空");
        String episodeId = latest.getLatestEpisodeId();
        if (episodeId != null && !episodeId.isBlank()) return episodeId.trim();
        Integer episodeNumber = latest.getLatestEpisodeNumber();
        if (episodeNumber != null) return "episode-number:" + episodeNumber;
        throw new IllegalArgumentException("最新作品快照缺少集数标识");
    }
}
