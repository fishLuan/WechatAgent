package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;

/** 只负责比较订阅基线和最新作品快照，不访问网络或数据库。 */
public final class BilibiliUpdateDetector {

    public boolean hasNewEpisode(
        BilibiliSubscription subscription, BilibiliContent latest
    ) {
        requireComparable(subscription, latest);
        Integer knownNumber = subscription.getLastKnownEpisodeNumber();
        Integer latestNumber = latest.getLatestEpisodeNumber();
        if (knownNumber != null && latestNumber != null) {
            return latestNumber > knownNumber;
        }

        String knownEpisodeId = normalized(subscription.getLastKnownEpisodeId());
        String latestEpisodeId = normalized(latest.getLatestEpisodeId());
        if (knownEpisodeId == null || latestEpisodeId == null) {
            // 没有旧基线时只建立基线，不能把订阅前已有剧集误判成更新。
            return false;
        }
        return !knownEpisodeId.equals(latestEpisodeId);
    }

    public boolean hasBaseline(BilibiliSubscription subscription) {
        return subscription.getLastKnownEpisodeNumber() != null
            || normalized(subscription.getLastKnownEpisodeId()) != null;
    }

    private void requireComparable(
        BilibiliSubscription subscription, BilibiliContent latest
    ) {
        if (subscription == null || latest == null) {
            throw new IllegalArgumentException("订阅和最新作品快照不能为空");
        }
        if (subscription.getContentType() == null
            || !subscription.getContentType().isEpisodeTrackable()) {
            throw new IllegalArgumentException("该内容类型不支持按集追更");
        }
        if (latest.getContentType() != subscription.getContentType()) {
            throw new IllegalArgumentException("最新作品快照与订阅内容类型不一致");
        }
        if (latest.getContentId() == null
            || !latest.getContentId().equals(subscription.getContentId())) {
            throw new IllegalArgumentException("最新作品快照与订阅作品不一致");
        }
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
