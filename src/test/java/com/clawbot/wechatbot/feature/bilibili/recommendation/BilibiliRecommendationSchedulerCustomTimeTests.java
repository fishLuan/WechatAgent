package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliNotificationPort;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BilibiliRecommendationSchedulerCustomTimeTests {

    @Test
    void userCustomTimeIsNotBlockedByGlobalDefaultTime() {
        LocalTime now = LocalTime.now().withSecond(0).withNano(0);
        BilibiliProperties properties = new BilibiliProperties();
        properties.setEnabled(true);
        properties.setDefaultPushTime(now.plusHours(3));

        BilibiliRecommendationService recommendations =
            mock(BilibiliRecommendationService.class);
        BilibiliPreferenceServiceImpl preferences =
            mock(BilibiliPreferenceServiceImpl.class);
        BilibiliPreference preference =
            new BilibiliPreference("user-1", ContentType.BANGUMI);
        preference.setPushTime(now);
        preference.setRecommendationCount(3);
        preference.setPushEnabled(true);
        when(preferences.findAllWithPushEnabled(ContentType.BANGUMI))
            .thenReturn(List.of(preference));
        when(preferences.findAllWithPushEnabled(ContentType.SERIES))
            .thenReturn(List.of());
        when(preferences.findAllWithPushEnabled(ContentType.MOVIE))
            .thenReturn(List.of());
        when(recommendations.recommend("user-1", ContentType.BANGUMI, 3))
            .thenReturn(result());

        AtomicInteger notifications = new AtomicInteger();
        BilibiliRecommendationScheduler scheduler =
            new BilibiliRecommendationScheduler(
                properties, recommendations, preferences);
        scheduler.setNotificationPort(new BilibiliNotificationPort() {
            @Override
            public void notifyEpisodeUpdate(
                String userId, EpisodeUpdateNotification notification
            ) {
            }

            @Override
            public void notifyDailyRecommendation(
                String userId, RecommendationResult recommendation
            ) {
                notifications.incrementAndGet();
            }
        });

        scheduler.checkAndPush();

        assertEquals(1, notifications.get());
    }

    private RecommendationResult result() {
        RecommendedContent item = new RecommendedContent(
            ContentType.BANGUMI,
            "content-1",
            "season-1",
            "测试动漫",
            9.8,
            Set.of("动画"),
            "https://www.bilibili.com/bangumi/play/ss1",
            "更新中",
            "评分 9.8");
        return new RecommendationResult(
            "user-1", ContentType.BANGUMI, List.of(item), Instant.now());
    }
}
