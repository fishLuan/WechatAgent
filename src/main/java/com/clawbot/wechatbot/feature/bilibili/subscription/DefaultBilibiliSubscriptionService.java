package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.CheckResult;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

/** 角色四提供的追更订阅默认实现。 */
public final class DefaultBilibiliSubscriptionService
    implements BilibiliSubscriptionService {

    private final BilibiliSubscriptionRepository repository;
    private final BilibiliContentSource contentSource;
    private final BilibiliSubscriptionCheckService checkService;
    private final Clock clock;

    public DefaultBilibiliSubscriptionService(
        BilibiliSubscriptionRepository repository,
        BilibiliContentSource contentSource,
        BilibiliSubscriptionCheckService checkService,
        Clock clock
    ) {
        this.repository = repository;
        this.contentSource = contentSource;
        this.checkService = checkService;
        this.clock = clock;
    }

    @Override
    public SubscriptionResult subscribeByUrl(
        String wechatUserId, String bilibiliUrl
    ) {
        try {
            if (bilibiliUrl == null || bilibiliUrl.isBlank()) {
                throw new IllegalArgumentException("B站作品链接不能为空");
            }
            return toResult(subscribe(
                wechatUserId, contentSource.resolveUrl(bilibiliUrl.trim())));
        } catch (Exception error) {
            return failedSubscription(error);
        }
    }

    @Override
    public SubscriptionResult subscribeByContentId(
        String wechatUserId, ContentType contentType, String contentId
    ) {
        try {
            if (contentType == null) {
                throw new IllegalArgumentException("contentType 不能为空");
            }
            String safeContentId = requireText(contentId, "contentId");
            BilibiliContent content = contentSource.findByContentId(
                    contentType, safeContentId)
                .orElseThrow(() -> new NoSuchElementException("未找到对应的B站作品"));
            return toResult(subscribe(
                wechatUserId, contentSource.refresh(content)));
        } catch (Exception error) {
            return failedSubscription(error);
        }
    }

    @Override
    public List<SubscriptionView> listSubscriptions(String wechatUserId) {
        String userId = requireText(wechatUserId, "wechatUserId");
        return Stream.concat(
                repository.findByWechatUserIdAndStatus(
                    userId, SubscriptionStatus.ACTIVE).stream(),
                repository.findByWechatUserIdAndStatus(
                    userId, SubscriptionStatus.PAUSED).stream())
            .sorted(Comparator.comparing(
                BilibiliSubscription::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toView)
            .toList();
    }

    @Override
    public OperationResult pause(String wechatUserId, String subscriptionId) {
        return changeStatus(
            wechatUserId,
            subscriptionId,
            SubscriptionStatus.PAUSED,
            "订阅已暂停");
    }

    @Override
    public OperationResult resume(String wechatUserId, String subscriptionId) {
        return changeStatus(
            wechatUserId,
            subscriptionId,
            SubscriptionStatus.ACTIVE,
            "订阅已恢复");
    }

    @Override
    public OperationResult cancel(String wechatUserId, String subscriptionId) {
        return changeStatus(
            wechatUserId,
            subscriptionId,
            SubscriptionStatus.CANCELLED,
            "订阅已取消");
    }

    @Override
    public CheckResult checkNow(String wechatUserId) {
        String userId = requireText(wechatUserId, "wechatUserId");
        List<BilibiliSubscription> subscriptions =
            repository.findByWechatUserIdAndStatus(
                userId, SubscriptionStatus.ACTIVE);
        List<EpisodeUpdateNotification> updates = new ArrayList<>();
        for (BilibiliSubscription subscription : subscriptions) {
            try {
                checkService.check(subscription).notification()
                    .ifPresent(updates::add);
            } catch (Exception error) {
                System.err.println(
                    "[BILIBILI] 用户主动检查失败 subscriptionId="
                        + subscription.getId() + "：" + safeMessage(error));
            }
        }
        return new CheckResult(
            subscriptions.size(), updates.size(), updates, clock.instant());
    }

    private SubscribeOutcome subscribe(
        String wechatUserId, BilibiliContent content
    ) {
        validateTrackable(content);
        String userId = requireText(wechatUserId, "wechatUserId");
        Optional<BilibiliSubscription> existing =
            repository.findByWechatUserIdAndSeasonId(userId, content.getSeasonId());
        if (existing.isPresent()) {
            return new SubscribeOutcome(
                reactivate(existing.get(), content), true);
        }

        BilibiliSubscription subscription = new BilibiliSubscription(
            userId,
            content.getContentType(),
            content.getContentId(),
            content.getSeasonId());
        applySnapshot(subscription, content);
        try {
            return new SubscribeOutcome(repository.insert(subscription), false);
        } catch (DuplicateKeyException duplicate) {
            BilibiliSubscription concurrentlyCreated =
                repository.findByWechatUserIdAndSeasonId(
                        userId, content.getSeasonId())
                    .orElseThrow(() -> duplicate);
            return new SubscribeOutcome(
                reactivate(concurrentlyCreated, content), true);
        }
    }

    private BilibiliSubscription reactivate(
        BilibiliSubscription subscription, BilibiliContent latest
    ) {
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        applySnapshot(subscription, latest);
        return repository.save(subscription);
    }

    private OperationResult changeStatus(
        String wechatUserId,
        String subscriptionId,
        SubscriptionStatus targetStatus,
        String successMessage
    ) {
        try {
            String userId = requireText(wechatUserId, "wechatUserId");
            String id = requireText(subscriptionId, "subscriptionId");
            BilibiliSubscription subscription = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("订阅不存在"));
            if (!userId.equals(subscription.getWechatUserId())) {
                throw new NoSuchElementException("订阅不存在");
            }
            subscription.setStatus(targetStatus);
            subscription.setUpdatedAt(clock.instant());
            repository.save(subscription);
            return OperationResult.succeeded(successMessage);
        } catch (RuntimeException error) {
            return OperationResult.failed(safeMessage(error));
        }
    }

    private void applySnapshot(
        BilibiliSubscription subscription, BilibiliContent content
    ) {
        subscription.setTitle(content.getTitle());
        subscription.setLastKnownEpisodeId(content.getLatestEpisodeId());
        subscription.setLastKnownEpisodeNumber(content.getLatestEpisodeNumber());
        subscription.setUpdatedAt(clock.instant());
    }

    private void validateTrackable(BilibiliContent content) {
        if (content == null) throw new IllegalArgumentException("作品不能为空");
        if (content.getContentType() == null
            || !content.getContentType().isEpisodeTrackable()) {
            throw new IllegalArgumentException(
                "电影不支持按集追更，请将该作品标记为“想看”");
        }
        requireText(content.getContentId(), "contentId");
        requireText(content.getSeasonId(), "seasonId");
    }

    private SubscriptionResult toResult(SubscribeOutcome outcome) {
        BilibiliSubscription value = outcome.subscription();
        return new SubscriptionResult(
            true,
            outcome.alreadySubscribed(),
            value.getId(),
            value.getTitle(),
            value.getSeasonId(),
            value.getStatus(),
            value.getLastKnownEpisodeNumber(),
            outcome.alreadySubscribed() ? "订阅已存在，已恢复追更" : "订阅成功");
    }

    private SubscriptionResult failedSubscription(Exception error) {
        return new SubscriptionResult(
            false, false, null, null, null, null, null, safeMessage(error));
    }

    private SubscriptionView toView(BilibiliSubscription value) {
        return new SubscriptionView(
            value.getId(),
            value.getContentType(),
            value.getContentId(),
            value.getSeasonId(),
            value.getTitle(),
            value.getStatus(),
            value.getLastKnownEpisodeNumber());
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null
            ? error.getClass().getSimpleName()
            : error.getMessage();
    }

    private record SubscribeOutcome(
        BilibiliSubscription subscription, boolean alreadySubscribed
    ) {
    }
}
