package com.clawbot.wechatbot.feature.bilibili.model;

/** 新增订阅操作的返回结果。 */
public record SubscriptionResult(
    boolean success,
    boolean alreadySubscribed,
    String subscriptionId,
    String title,
    String seasonId,
    SubscriptionStatus status,
    Integer latestEpisodeNumber,
    String message
) {
}
