package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 周期检查所有有效追更订阅；单个订阅失败不会中断整批任务。 */
public final class BilibiliSubscriptionScheduler {
    private final BilibiliSubscriptionRepository repository;
    private final BilibiliSubscriptionCheckService checkService;

    public BilibiliSubscriptionScheduler(
        BilibiliSubscriptionRepository repository,
        BilibiliSubscriptionCheckService checkService
    ) {
        this.repository = repository;
        this.checkService = checkService;
    }

    @Scheduled(
        fixedDelayString = "${clawbot.bilibili.subscription-check-interval-minutes}",
        initialDelayString = "${clawbot.bilibili.subscription-check-interval-minutes}",
        timeUnit = TimeUnit.MINUTES
    )
    public void scheduledCheck() {
        checkNow();
    }

    public CheckSummary checkNow() {
        List<BilibiliSubscription> subscriptions =
            repository.findByStatus(SubscriptionStatus.ACTIVE);
        int checked = 0;
        int eventsCreated = 0;
        int failures = 0;
        for (BilibiliSubscription subscription : subscriptions) {
            try {
                if (checkService.check(subscription).eventCreated()) eventsCreated++;
                checked++;
            } catch (Exception error) {
                failures++;
                System.err.println(
                    "[BILIBILI] 订阅检查失败 subscriptionId="
                        + safeId(subscription) + "："
                        + safeMessage(error));
            }
        }
        return new CheckSummary(subscriptions.size(), checked, eventsCreated, failures);
    }

    private String safeId(BilibiliSubscription subscription) {
        return subscription == null || subscription.getId() == null
            ? "unknown"
            : subscription.getId();
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null
            ? error.getClass().getSimpleName()
            : error.getMessage();
    }

    public record CheckSummary(
        int scheduled,
        int checked,
        int eventsCreated,
        int failures
    ) {
    }
}
