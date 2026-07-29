package com.clawbot.wechatbot.feature.bilibili.model;

/** 面向交互层的订阅只读视图。 */
public record SubscriptionView(
    String subscriptionId,
    ContentType contentType,
    String contentId,
    String seasonId,
    String title,
    SubscriptionStatus status,
    Integer latestEpisodeNumber
) {
}
