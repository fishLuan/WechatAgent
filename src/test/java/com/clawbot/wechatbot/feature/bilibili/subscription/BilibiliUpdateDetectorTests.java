package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliUpdateDetectorTests {
    private final BilibiliUpdateDetector detector = new BilibiliUpdateDetector();

    @Test
    void missingBaselineOnlyEstablishesBaseline() {
        BilibiliSubscription subscription = subscription();
        BilibiliContent latest = content(12, "ep-12");

        assertFalse(detector.hasBaseline(subscription));
        assertFalse(detector.hasNewEpisode(subscription, latest));
    }

    @Test
    void comparesEpisodeNumberWhenBothSidesHaveOne() {
        BilibiliSubscription subscription = subscription();
        subscription.setLastKnownEpisodeNumber(11);

        assertTrue(detector.hasNewEpisode(subscription, content(12, "ep-12")));
        assertFalse(detector.hasNewEpisode(subscription, content(11, "changed-id")));
        assertFalse(detector.hasNewEpisode(subscription, content(10, "ep-10")));
    }

    @Test
    void fallsBackToEpisodeIdWhenNumbersAreUnavailable() {
        BilibiliSubscription subscription = subscription();
        subscription.setLastKnownEpisodeId("ep-old");

        assertTrue(detector.hasNewEpisode(subscription, content(null, "ep-new")));
        assertFalse(detector.hasNewEpisode(subscription, content(null, "ep-old")));
    }

    @Test
    void rejectsMismatchedContentSnapshot() {
        BilibiliSubscription subscription = subscription();
        BilibiliContent latest =
            new BilibiliContent(ContentType.SERIES, "another", "其他作品");

        assertThrows(
            IllegalArgumentException.class,
            () -> detector.hasNewEpisode(subscription, latest));
    }

    private BilibiliSubscription subscription() {
        return new BilibiliSubscription(
            "user-1", ContentType.BANGUMI, "content-1", "season-1");
    }

    private BilibiliContent content(Integer episodeNumber, String episodeId) {
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, "content-1", "测试番剧");
        content.setSeasonId("season-1");
        content.setLatestEpisodeNumber(episodeNumber);
        content.setLatestEpisodeId(episodeId);
        return content;
    }
}
