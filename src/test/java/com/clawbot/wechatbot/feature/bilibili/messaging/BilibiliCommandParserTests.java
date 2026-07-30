package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliCommandParserTests {

    // ============================
    // 1. 今日推荐三类：动漫、电影、剧集（电视剧）
    // ============================
    @Test
    void parsesAnimeRecommendationCommands() {
        assertCmdType("今日动漫推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_ANIME);
        assertCmdType("动漫推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_ANIME);
        assertCmdType("今天有啥好看的番", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_ANIME);
        assertCmdType("每日动漫推荐一下", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_ANIME);
        assertCmdType("推荐点番剧看看", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_ANIME);
        assertContentType("动漫推荐", ContentType.BANGUMI);
    }

    @Test
    void parsesMovieRecommendationCommands() {
        assertCmdType("今日电影推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_MOVIE);
        assertCmdType("电影推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_MOVIE);
        assertCmdType("今天电影有啥好看的", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_MOVIE);
        assertCmdType("每日电影推一下", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_MOVIE);
        assertContentType("电影推荐", ContentType.MOVIE);
    }

    @Test
    void parsesSeriesRecommendationCommands() {
        assertCmdType("今日剧集推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("电视剧推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("今日电视剧推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("最近有啥好看的剧", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("推荐几部美剧看看", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("日剧推荐一下", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("韩剧有啥好看的", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertCmdType("剧集推荐", BilibiliCommandParser.CmdType.TODAY_RECOMMEND_SERIES);
        assertContentType("国产剧推荐", ContentType.SERIES);
    }

    // ============================
    // 2. 编号订阅/标记状态：订阅2 / 想看3 / 看过1 / 不喜欢3
    // ============================
    @Test
    void parsesIndexSubscribeCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("订阅2");
        assertEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_INDEX, c.type());
        assertEquals(2, c.index());

        c = BilibiliCommandParser.parse("追更 10 ");  // 前后有空格
        assertEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_INDEX, c.type());
        assertEquals(10, c.index());

        c = BilibiliCommandParser.parse("订阅 1");
        assertEquals(1, c.index());
    }

    @Test
    void parsesIndexMarkStateCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("想看2");
        assertEquals(BilibiliCommandParser.CmdType.MARK_WANT_TO_WATCH, c.type());
        assertEquals(2, c.index());

        c = BilibiliCommandParser.parse("看过1");
        assertEquals(BilibiliCommandParser.CmdType.MARK_WATCHED, c.type());
        assertEquals(1, c.index());

        c = BilibiliCommandParser.parse("不喜欢 3");
        assertEquals(BilibiliCommandParser.CmdType.MARK_DISLIKED, c.type());
        assertEquals(3, c.index());
    }

    @Test
    void rejectsInvalidIndexCommands() {
        // 两位数以上不支持（保护用户避免误操作）
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("订阅123");
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN, c.type());

        // 没有编号
        c = BilibiliCommandParser.parse("订阅");
        // 这个不会命中 IDX_PATTERN，但是有可能命中其他？应该是 UNKNOWN
        assertNotEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_INDEX, c.type());
    }

    // ============================
    // 3. B站链接订阅（bilibili.com / b23.tv）
    // ============================
    @Test
    void parsesBilibiliUrls() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse(
            "帮我订阅这个 https://www.bilibili.com/bangumi/play/ss12345?spm_id_from=xxx");
        assertEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_URL, c.type());
        assertTrue(c.url().startsWith("https://"));

        // 短链
        c = BilibiliCommandParser.parse("https://b23.tv/abc123");
        assertEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_URL, c.type());

        // m 站移动端链接
        c = BilibiliCommandParser.parse("看这个剧 https://m.bilibili.com/bangumi/play/ep987");
        assertEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_URL, c.type());
    }

    @Test
    void removesPoliteParticleFromSubscriptionTitle() {
        BilibiliCommandParser.ParsedCommand command =
            BilibiliCommandParser.parse("订阅一下牧神记");
        assertEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_TITLE, command.type());
        assertEquals("牧神记", command.title());

        command = BilibiliCommandParser.parse("帮我订阅一下《牧神记》");
        assertEquals("牧神记", command.title());

        command = BilibiliCommandParser.parse("我想追更一下牧神记");
        assertEquals("牧神记", command.title());

        command = BilibiliCommandParser.parse("订阅牧神记");
        assertEquals("牧神记", command.title());
    }

    // ============================
    // 4. 查看/取消/暂停/恢复订阅
    // ============================
    @Test
    void parsesSubscriptionListCommands() {
        assertCmdType("我的订阅", BilibiliCommandParser.CmdType.LIST_SUBSCRIPTIONS);
        assertCmdType("查看订阅", BilibiliCommandParser.CmdType.LIST_SUBSCRIPTIONS);
        assertCmdType("追更列表", BilibiliCommandParser.CmdType.LIST_SUBSCRIPTIONS);
        assertCmdType("订阅列表", BilibiliCommandParser.CmdType.LIST_SUBSCRIPTIONS);
        assertCmdType("列出订阅", BilibiliCommandParser.CmdType.LIST_SUBSCRIPTIONS);
    }

    @Test
    void parsesCancelSubscriptionCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("取消订阅2");
        assertEquals(BilibiliCommandParser.CmdType.CANCEL_SUBSCRIPTION, c.type());
        assertEquals(2, c.index());

        c = BilibiliCommandParser.parse("删除订阅 3");
        assertEquals(3, c.index());

        c = BilibiliCommandParser.parse("取消订阅 6a681e9a3c990e466d4cb260");
        assertEquals(BilibiliCommandParser.CmdType.CANCEL_SUBSCRIPTION, c.type());
        assertEquals("6a681e9a3c990e466d4cb260", c.subscriptionId());
    }

    @Test
    void parsesPauseResumeSubscriptionCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("暂停订阅1");
        assertEquals(BilibiliCommandParser.CmdType.PAUSE_SUBSCRIPTION, c.type());
        assertEquals(1, c.index());

        c = BilibiliCommandParser.parse("恢复订阅 2");
        assertEquals(BilibiliCommandParser.CmdType.RESUME_SUBSCRIPTION, c.type());
        assertEquals(2, c.index());

        c = BilibiliCommandParser.parse("继续订阅 6a681e9a3c990e466d4cb260");
        assertEquals(BilibiliCommandParser.CmdType.RESUME_SUBSCRIPTION, c.type());
        assertEquals("6a681e9a3c990e466d4cb260", c.subscriptionId());
    }

    // ============================
    // 5. 偏好设置：时间/评分/数量（含中文冒号替换）
    // ============================
    @Test
    void parsesSetPushTimeCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("设置动漫推送时间 21:00");
        assertEquals(BilibiliCommandParser.CmdType.SET_PUSH_TIME, c.type());
        assertEquals(ContentType.BANGUMI, c.contentType());
        assertEquals("21:00", c.fieldValue());

        // 中文冒号！关键边界
        c = BilibiliCommandParser.parse("设置电影推送时间 20：30");
        assertEquals(BilibiliCommandParser.CmdType.SET_PUSH_TIME, c.type());
        assertEquals(ContentType.MOVIE, c.contentType());
        assertEquals("20:30", c.fieldValue());

        // 剧集设置
        c = BilibiliCommandParser.parse("设置剧集推送时间 19:45");
        assertEquals(ContentType.SERIES, c.contentType());
        assertEquals("19:45", c.fieldValue());
    }

    @Test
    void parsesNaturalDailyMoviePushBeforeImmediateRecommendation() {
        BilibiliCommandParser.ParsedCommand command =
            BilibiliCommandParser.parse("每天晚上十点十分给我推送3部9.2分以上的高分电影");

        assertEquals(
            BilibiliCommandParser.CmdType.CONFIGURE_DAILY_RECOMMENDATION,
            command.type());
        assertEquals(ContentType.MOVIE, command.contentType());
        assertEquals("22:10", command.fieldValue());
        assertEquals(9.2, command.minimumRating());
        assertEquals(3, command.recommendationCount());
        assertTrue(command.pushEnabled());
    }

    @Test
    void parsesSetMinRatingCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("设置电影最低评分 9.5");
        assertEquals(BilibiliCommandParser.CmdType.SET_MIN_RATING, c.type());
        assertEquals(ContentType.MOVIE, c.contentType());
        assertEquals("9.5", c.fieldValue());

        c = BilibiliCommandParser.parse("设置动漫最低评分 8");
        assertEquals(BilibiliCommandParser.CmdType.SET_MIN_RATING, c.type());
        assertEquals("8", c.fieldValue());
    }

    @Test
    void parsesSetCountCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("设置动漫推荐数量 5");
        assertEquals(BilibiliCommandParser.CmdType.SET_RECOMMEND_COUNT, c.type());
        assertEquals(ContentType.BANGUMI, c.contentType());
        assertEquals("5", c.fieldValue());

        c = BilibiliCommandParser.parse("设置电影推荐数量 10");
        assertEquals(BilibiliCommandParser.CmdType.SET_RECOMMEND_COUNT, c.type());
        assertEquals("10", c.fieldValue());
    }

    @Test
    void parsesTogglePushCommands() {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("开启动漫推送");
        assertEquals(BilibiliCommandParser.CmdType.TOGGLE_PUSH, c.type());
        assertEquals(ContentType.BANGUMI, c.contentType());
        assertTrue(c.pushEnabled());

        c = BilibiliCommandParser.parse("关闭电影每日推荐");
        assertEquals(BilibiliCommandParser.CmdType.TOGGLE_PUSH, c.type());
        assertEquals(ContentType.MOVIE, c.contentType());
        assertFalse(c.pushEnabled());

        c = BilibiliCommandParser.parse("禁用剧集推送");
        assertFalse(c.pushEnabled());
    }

    @Test
    void parsesExcludedAndRestoredWeekdays() {
        BilibiliCommandParser.ParsedCommand command =
            BilibiliCommandParser.parse("电影周六周日不推送");
        assertEquals(
            BilibiliCommandParser.CmdType.SET_WEEKDAY_PUSH_POLICY,
            command.type());
        assertEquals(ContentType.MOVIE, command.contentType());
        assertEquals("SATURDAY,SUNDAY", command.fieldValue());
        assertEquals("exclude", command.state());

        command = BilibiliCommandParser.parse("恢复周六动漫推送");
        assertEquals(ContentType.BANGUMI, command.contentType());
        assertEquals("SATURDAY", command.fieldValue());
        assertEquals("include", command.state());

        command = BilibiliCommandParser.parse("星期三不要推送");
        assertNull(command.contentType());
        assertEquals("WEDNESDAY", command.fieldValue());
    }

    // ============================
    // 6. 查看设置 + 立即检查更新
    // ============================
    @Test
    void parsesPreferenceShowAndCheckNow() {
        assertCmdType("查看偏好", BilibiliCommandParser.CmdType.SHOW_PREFERENCES);
        assertCmdType("我的设置", BilibiliCommandParser.CmdType.SHOW_PREFERENCES);
        assertCmdType("显示推荐设置", BilibiliCommandParser.CmdType.SHOW_PREFERENCES);

        assertCmdType("检查更新", BilibiliCommandParser.CmdType.CHECK_UPDATES_NOW);
        assertCmdType("立即检查更新", BilibiliCommandParser.CmdType.CHECK_UPDATES_NOW);
        assertCmdType("刷新更新", BilibiliCommandParser.CmdType.CHECK_UPDATES_NOW);
    }

    // ============================
    // 7. 边界条件：空白、非B站命令（UNKNOWN）
    // ============================
    @Test
    void unknownCommandsAndEdgeCases() {
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN, BilibiliCommandParser.parse(null).type());
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN, BilibiliCommandParser.parse("").type());
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN, BilibiliCommandParser.parse("   ").type());

        // 普通聊天，不是B站命令 -> UNKNOWN，交给 AI
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN,
            BilibiliCommandParser.parse("今天天气怎么样").type());
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN,
            BilibiliCommandParser.parse("你好呀，我想设置定时任务").type());
        // 非 B 站链接
        assertEquals(BilibiliCommandParser.CmdType.UNKNOWN,
            BilibiliCommandParser.parse("看这个 https://www.douban.com/movie123").type());
    }

    // ============================
    // 8. 文档要求的电影测试：「想看2」只能匹配
    // ============================
    @Test
    void wantToWatchForMovieDoesNotMatchSubscribe() {
        // 用户对电影推荐回复「想看2」 -> 解析成 MARK_WANT_TO_WATCH，不是 SUBSCRIBE_BY_INDEX
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse("想看2");
        assertEquals(BilibiliCommandParser.CmdType.MARK_WANT_TO_WATCH, c.type());
        assertNotEquals(BilibiliCommandParser.CmdType.SUBSCRIBE_BY_INDEX, c.type());
    }

    // ============================
    // 辅助方法
    // ============================
    private void assertCmdType(String input, BilibiliCommandParser.CmdType expected) {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse(input);
        assertEquals(expected, c.type(),
            "解析命令失败: input=「" + input + "」期望=" + expected + " 实际=" + c.type());
    }

    private void assertContentType(String input, ContentType expectedType) {
        BilibiliCommandParser.ParsedCommand c = BilibiliCommandParser.parse(input);
        assertEquals(expectedType, c.contentType(),
            "contentType 不匹配: input=「" + input + "」");
    }
}
