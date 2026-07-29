package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.RecommendationHistoryService;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliCommandHandlerTests {
    private BilibiliSubscriptionService subscriptionService;
    private BilibiliRecommendationService recommendationService;
    private BilibiliContentSource contentSource;
    private RecommendationHistoryService historyService;
    private BilibiliCommandHandler handler;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(BilibiliSubscriptionService.class);
        recommendationService = mock(BilibiliRecommendationService.class);
        contentSource = mock(BilibiliContentSource.class);
        historyService = mock(RecommendationHistoryService.class);
        BilibiliProperties properties = new BilibiliProperties();
        handler = new BilibiliCommandHandler(
            subscriptionService,
            recommendationService,
            mock(BilibiliPreferenceService.class),
            mock(SchedulerControlService.class),
            new WeChatSessionRegistry(),
            new ObjectMapper(),
            contentSource,
            properties,
            historyService);
    }

    @Test
    void subscribesToBangumiUsingSeasonId() {
        RecommendedContent item = item("media-28235382", "39444");
        when(recommendationService.findPendingItem("user-1", 2)).thenReturn(item);
        when(subscriptionService.subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "39444"))
            .thenReturn(success());

        handler.handleSubscribeByIndex("user-1", 2, null);

        verify(subscriptionService).subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "39444");
    }

    @Test
    void subscribesToPgcSeriesUsingSeasonId() {
        RecommendedContent item = item(
            ContentType.SERIES, "media-28223067", "38729");
        when(recommendationService.findPendingItem("user-1", 2)).thenReturn(item);
        when(subscriptionService.subscribeBySeasonId(
            "user-1", ContentType.SERIES, "38729"))
            .thenReturn(success());

        handler.handleSubscribeByIndex("user-1", 2, null);

        verify(subscriptionService).subscribeBySeasonId(
            "user-1", ContentType.SERIES, "38729");
    }

    @Test
    void fallsBackToContentIdWhenSeasonIdIsMissing() {
        RecommendedContent item = item("media-28235382", null);
        when(recommendationService.findPendingItem("user-1", 2)).thenReturn(item);
        when(subscriptionService.subscribeByContentId(
            "user-1", ContentType.BANGUMI, "media-28235382"))
            .thenReturn(success());

        handler.handleSubscribeByIndex("user-1", 2, null);

        verify(subscriptionService).subscribeByContentId(
            "user-1", ContentType.BANGUMI, "media-28235382");
    }

    @Test
    void searchesByTitleAndReturnsCandidateUrl() throws Exception {
        BilibiliContent content =
            new BilibiliContent(ContentType.SERIES, "28223067", "老友记 第一季");
        content.setSeasonId("38729");
        content.setPageUrl(
            "https://www.bilibili.com/bangumi/play/ss38729");
        content.setRating(9.9);
        when(contentSource.searchByTitle("老友记", 5))
            .thenReturn(List.of(content));

        String reply = handler.handle("user-1", "搜索 老友记");

        assertTrue(reply.contains("老友记 第一季"));
        assertTrue(reply.contains(
            "https://www.bilibili.com/bangumi/play/ss38729"));
        verify(contentSource).searchByTitle("老友记", 5);
    }

    @Test
    void subscribesDirectlyWhenTitleHasOneExactUnfinishedMatch()
        throws Exception {
        BilibiliContent content =
            new BilibiliContent(
                ContentType.BANGUMI,
                "media-violet",
                "紫罗兰永恒花园");
        content.setSeasonId("21542");
        content.setFinished(false);
        when(contentSource.searchByTitle("紫罗兰的永恒花园", 5))
            .thenReturn(List.of(content));
        when(subscriptionService.subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "21542"))
            .thenReturn(success());

        String reply = handler.handleSubscribeByTitle(
            "user-1", "紫罗兰的永恒花园");

        assertTrue(reply.contains("订阅成功"));
        verify(subscriptionService).subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "21542");
    }

    @Test
    void returnsCandidatesWhenTitleSearchIsAmbiguous() throws Exception {
        BilibiliContent first =
            new BilibiliContent(ContentType.BANGUMI, "media-1", "作品 第一季");
        first.setPageUrl("https://www.bilibili.com/bangumi/play/ss1");
        BilibiliContent second =
            new BilibiliContent(ContentType.BANGUMI, "media-2", "作品 第二季");
        second.setPageUrl("https://www.bilibili.com/bangumi/play/ss2");
        when(contentSource.searchByTitle("作品", 5))
            .thenReturn(List.of(first, second));

        String reply = handler.handleSubscribeByTitle("user-1", "作品");

        assertTrue(reply.contains("找到多个相关作品"));
        assertTrue(reply.contains(
            "https://www.bilibili.com/bangumi/play/ss1"));
        assertTrue(reply.contains(
            "https://www.bilibili.com/bangumi/play/ss2"));
    }

    @Test
    void marksExactTitleAsWatched() throws Exception {
        BilibiliContent content = new BilibiliContent(
            ContentType.MOVIE,
            "media-red",
            "航海王：红发歌姬");
        when(contentSource.searchByTitle("航海王：红发歌姬", 5))
            .thenReturn(List.of(content));

        String reply = handler.handleMarkStateByTitle(
            "user-1", "航海王：红发歌姬", "watched");

        assertTrue(reply.contains("标记为看过"));
        verify(historyService).markWatched(
            "user-1",
            ContentType.MOVIE,
            "media-red",
            "航海王：红发歌姬");
    }

    @Test
    void doesNotGuessWhenStateTitleIsAmbiguous() throws Exception {
        BilibiliContent first = new BilibiliContent(
            ContentType.BANGUMI, "media-1", "航海王 第一季");
        BilibiliContent second = new BilibiliContent(
            ContentType.MOVIE, "media-2", "航海王：红发歌姬");
        when(contentSource.searchByTitle("航海王", 5))
            .thenReturn(List.of(first, second));

        String reply = handler.handleMarkStateByTitle(
            "user-1", "航海王", "watched");

        assertTrue(reply.contains("多个相关作品"));
        verify(historyService, org.mockito.Mockito.never())
            .markWatched(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void persistsWantToWatchByRecommendationIndex() {
        RecommendedContent content = item(
            ContentType.BANGUMI, "media-want", "season-want");
        when(recommendationService.findPendingItem("user-1", 2))
            .thenReturn(content);

        String reply = handler.handleMarkState(
            "user-1", 2, "want_to_watch");

        assertTrue(reply.contains("标记想看"));
        verify(historyService).markWantToWatch(
            "user-1",
            ContentType.BANGUMI,
            "media-want",
            "测试动漫");
    }

    private RecommendedContent item(String contentId, String seasonId) {
        return item(ContentType.BANGUMI, contentId, seasonId);
    }

    private RecommendedContent item(
        ContentType contentType, String contentId, String seasonId
    ) {
        return new RecommendedContent(
            contentType,
            contentId,
            seasonId,
            "测试动漫",
            9.8,
            Set.of("动画"),
            "https://www.bilibili.com/bangumi/play/ss39444",
            "全 7 话",
            "评分 9.8");
    }

    private SubscriptionResult success() {
        return new SubscriptionResult(
            true,
            false,
            "subscription-1",
            "测试动漫",
            "39444",
            SubscriptionStatus.ACTIVE,
            7,
            "订阅成功");
    }
}
