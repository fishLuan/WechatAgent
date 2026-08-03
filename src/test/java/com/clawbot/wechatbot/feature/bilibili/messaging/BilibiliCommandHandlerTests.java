package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.application.BilibiliCatalogCommandService;
import com.clawbot.wechatbot.feature.bilibili.application.BilibiliUpdateQueryService;
import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.PreferenceUpdate;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceCommandService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.RecommendationHistoryService;
import com.clawbot.wechatbot.feature.bilibili.rag.BilibiliRagService;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.scheduling.BilibiliSchedulePort;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class BilibiliCommandHandlerTests {
    private BilibiliSubscriptionService subscriptionService;
    private BilibiliRecommendationService recommendationService;
    private BilibiliContentSource contentSource;
    private RecommendationHistoryService historyService;
    private BilibiliPreferenceService preferenceService;
    private PendingSearchResultStore pendingSearchResults;
    private BilibiliRagService ragService;
    private BilibiliSchedulePort schedulePort;
    private BilibiliPreferenceCommandService preferenceCommands;
    private BilibiliContentRepository contentRepository;
    private BilibiliCommandHandler handler;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(BilibiliSubscriptionService.class);
        recommendationService = mock(BilibiliRecommendationService.class);
        contentSource = mock(BilibiliContentSource.class);
        historyService = mock(RecommendationHistoryService.class);
        preferenceService = mock(BilibiliPreferenceService.class);
        pendingSearchResults = new PendingSearchResultStore();
        ragService = mock(BilibiliRagService.class);
        schedulePort = mock(BilibiliSchedulePort.class);
        preferenceCommands = new BilibiliPreferenceCommandService(
            preferenceService, schedulePort);
        contentRepository = mock(BilibiliContentRepository.class);
        BilibiliProperties properties = new BilibiliProperties();
        BilibiliCatalogCommandService catalogCommands = new BilibiliCatalogCommandService(
            subscriptionService, recommendationService, historyService, contentSource,
            properties, pendingSearchResults);
        BilibiliUpdateQueryService updateQueries = new BilibiliUpdateQueryService(
            contentRepository, contentSource, historyService, preferenceService);
        handler = new BilibiliCommandHandler(
            subscriptionService,
            recommendationService,
            preferenceService,
            preferenceCommands,
            catalogCommands,
            updateQueries,
            new WeChatSessionRegistry(),
            ragService);
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
    void subscribesToThirdSearchResultUsingChineseOrdinal() throws Exception {
        BilibiliContent first = content("media-1", "ss1", "第一部", false);
        BilibiliContent second = content("media-2", "ss2", "第二部", false);
        BilibiliContent third = content("media-3", "ss3", "第三部", false);
        when(contentSource.searchByTitle("间谍过家家", 5))
            .thenReturn(List.of(first, second, third));
        when(subscriptionService.subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "ss3"))
            .thenReturn(success());

        handler.handleSearchByTitle("user-1", "间谍过家家");
        String reply = handler.handle("user-1", "订阅第三个");

        assertTrue(reply.contains("订阅成功"));
        verify(subscriptionService).subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "ss3");
    }

    @Test
    void doesNotSubscribeToFinishedSearchResult() throws Exception {
        BilibiliContent first = content("media-1", "ss1", "第一部", false);
        BilibiliContent second = content("media-2", "ss2", "第二部", false);
        BilibiliContent third = content("media-3", "ss3", "第三部", true);
        when(contentSource.searchByTitle("间谍过家家", 5))
            .thenReturn(List.of(first, second, third));

        handler.handleSearchByTitle("user-1", "间谍过家家");
        String reply = handler.handle("user-1", "订阅第三个");

        assertTrue(reply.contains("已经完结"));
        verify(subscriptionService, never()).subscribeBySeasonId(
            "user-1", ContentType.BANGUMI, "ss3");
        verify(subscriptionService, never()).subscribeByContentId(
            "user-1", ContentType.BANGUMI, "media-3");
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

    @Test
    void naturalDailyPushUpdatesPreferenceWithoutImmediateRecommendation() {
        BilibiliPreference current =
            new BilibiliPreference("user-1", ContentType.MOVIE);
        current.setPushTime(LocalTime.of(19, 30));
        current.setMinimumRating(8.0);
        current.setRecommendationCount(3);
        current.setPushEnabled(true);
        when(preferenceService.getOrCreate("user-1", ContentType.MOVIE))
            .thenReturn(current);
        BilibiliPreference saved =
            new BilibiliPreference("user-1", ContentType.MOVIE);
        saved.setPushTime(LocalTime.of(22, 10));
        saved.setMinimumRating(9.0);
        saved.setRecommendationCount(3);
        saved.setPushEnabled(true);
        when(preferenceService.update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            any(PreferenceUpdate.class))).thenReturn(saved);

        String reply = handler.handle(
            "user-1", "每天晚上十点十分给我推送高分电影");

        assertTrue(reply.contains("22:10"));
        verify(preferenceService).update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            any(PreferenceUpdate.class));
        verify(recommendationService, never()).recommend(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void compoundAnimeAndMoviePushUpdatesBothPreferences() {
        BilibiliPreference anime = preference(
            "user-1", ContentType.BANGUMI, LocalTime.of(20, 0), 9.0, 3);
        BilibiliPreference movie = preference(
            "user-1", ContentType.MOVIE, LocalTime.of(19, 30), 8.0, 3);
        when(preferenceService.getOrCreate("user-1", ContentType.BANGUMI))
            .thenReturn(anime);
        when(preferenceService.getOrCreate("user-1", ContentType.MOVIE))
            .thenReturn(movie);
        when(preferenceService.update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI),
            any(PreferenceUpdate.class)))
            .thenReturn(preference(
                "user-1", ContentType.BANGUMI, LocalTime.of(9, 20), 9.0, 3));
        when(preferenceService.update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            any(PreferenceUpdate.class)))
            .thenReturn(preference(
                "user-1", ContentType.MOVIE, LocalTime.of(9, 20), 8.0, 3));

        String reply = handler.handle(
            "user-1", "每天早上九点二十给我推送动漫和电影");

        assertTrue(reply.contains("09:20"));
        assertTrue(reply.contains("动漫"));
        assertTrue(reply.contains("电影"));
        verify(preferenceService).update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI),
            any(PreferenceUpdate.class));
        verify(preferenceService).update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            any(PreferenceUpdate.class));
        verify(recommendationService, never()).recommend(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void tenOclockPushConfiguresPreferenceWithoutImmediateRecommendation() {
        BilibiliPreference current = preference(
            "user-1", ContentType.MOVIE, LocalTime.of(19, 30), 8.0, 3);
        when(preferenceService.getOrCreate("user-1", ContentType.MOVIE))
            .thenReturn(current);
        when(preferenceService.update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            any(PreferenceUpdate.class)))
            .thenReturn(preference(
                "user-1", ContentType.MOVIE, LocalTime.of(10, 0), 8.0, 3));

        String reply = handler.handle("user-1", "10点给我推送电影");

        assertTrue(reply.contains("10:00"));
        verify(preferenceService).update(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            any(PreferenceUpdate.class));
        verify(recommendationService, never()).recommend(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void relativeTimeCreatesOneTimeBilibiliRecommendationTask() {
        when(preferenceService.getOrCreate("user-1", ContentType.MOVIE))
            .thenReturn(preference(
                "user-1", ContentType.MOVIE, LocalTime.of(19, 30), 8.0, 3));

        String reply = handler.handle("user-1", "两小时后推送电影");

        assertTrue(reply.contains("一次性任务"));
        verify(schedulePort).scheduleOneTime(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq(ContentType.MOVIE),
            org.mockito.ArgumentMatchers.eq(3),
            any(Instant.class));
        verify(recommendationService, never()).recommend(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void weekdayExclusionWithoutTypeAppliesToAllRecommendationTypes() {
        String reply = handler.handle("user-1", "周六不推送");

        assertTrue(reply.contains("周六不发送每日推荐"));
        for (ContentType type : List.of(
            ContentType.BANGUMI, ContentType.SERIES, ContentType.MOVIE
        )) {
            verify(preferenceService).setExcludedPushDays(
                "user-1", type, Set.of(DayOfWeek.SATURDAY), true);
        }
    }

    @Test
    void routesRagQuestionToRagService() {
        when(ragService.answer("user-1", "智能推荐动漫", ContentType.BANGUMI))
            .thenReturn("RAG 推荐结果");

        String reply = handler.handle("user-1", "智能推荐动漫");

        assertTrue(reply.contains("RAG 推荐结果"));
        verify(ragService).answer("user-1", "智能推荐动漫", ContentType.BANGUMI);
    }

    @Test
    void routesSimilarQuestionToRagService() {
        when(ragService.answerSimilar("user-1", "葬送的芙莉莲", ContentType.BANGUMI))
            .thenReturn("相似推荐结果");

        String reply = handler.handle("user-1", "推荐葬送的芙莉莲类似的番");

        assertTrue(reply.contains("相似推荐结果"));
        verify(ragService).answerSimilar("user-1", "葬送的芙莉莲", ContentType.BANGUMI);
    }

    @Test
    void queriesRecentUpdatesByBoundedTimeRange() {
        BilibiliContent update = content("media-new", "ss-new", "最近更新作品", false);
        update.setLatestEpisodePubTime(Instant.now().minusSeconds(3600));
        when(contentRepository.findUpdatesBetween(
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI), any(), any()))
            .thenReturn(List.of(update));
        when(historyService.findExcludedContentIds("user-1", ContentType.BANGUMI))
            .thenReturn(List.of());

        String reply = handler.handle("user-1", "查找最近更新的动漫");

        assertTrue(reply.contains("最近3天"));
        assertTrue(reply.contains("最近更新作品"));
        verify(contentRepository).findUpdatesBetween(
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI), any(), any());
    }

    @Test
    void fallsBackToVerifiedRealtimeUpdatesWhenDatabaseIsEmpty() throws Exception {
        BilibiliContent update = content("media-today", "ss-today", "今日更新作品", false);
        update.setLatestEpisodePubTime(Instant.now().minusSeconds(60));
        when(contentRepository.findUpdatesBetween(
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI), any(), any()))
            .thenReturn(List.of());
        when(contentSource.findUpdates(
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI), any(), any()))
            .thenReturn(List.of(update));
        when(historyService.findExcludedContentIds("user-1", ContentType.BANGUMI))
            .thenReturn(List.of());

        String reply = handler.handle("user-1", "查找今天更新的动漫");

        assertTrue(reply.contains("今日更新作品"));
        verify(contentRepository).saveAll(List.of(update));
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

    private BilibiliPreference preference(
        String userId,
        ContentType type,
        LocalTime pushTime,
        double minimumRating,
        int recommendationCount
    ) {
        BilibiliPreference preference = new BilibiliPreference(userId, type);
        preference.setPushTime(pushTime);
        preference.setMinimumRating(minimumRating);
        preference.setRecommendationCount(recommendationCount);
        preference.setPushEnabled(true);
        return preference;
    }

    private BilibiliContent content(
        String contentId,
        String seasonId,
        String title,
        boolean finished
    ) {
        BilibiliContent content =
            new BilibiliContent(ContentType.BANGUMI, contentId, title);
        content.setSeasonId(seasonId);
        content.setFinished(finished);
        return content;
    }
}
