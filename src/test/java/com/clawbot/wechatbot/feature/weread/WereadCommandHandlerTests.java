package com.clawbot.wechatbot.feature.weread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WereadCommandHandlerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WereadGatewayClient gateway;
    private WereadCommandHandler handler;

    @BeforeEach
    void setUp() {
        gateway = mock(WereadGatewayClient.class);
        handler = new WereadCommandHandler(gateway);
    }

    @Test
    void shelfCommandCallsShelfSyncAndListsBooks() throws Exception {
        when(gateway.call(eq("/shelf/sync"), any())).thenReturn(json(
            "{\"books\":["
                + "{\"title\":\"三体全集（全三册）\",\"author\":\"刘慈欣\",\"finishReading\":0},"
                + "{\"title\":\"认知觉醒\",\"author\":\"周岭\",\"finishReading\":1}"
                + "],\"albums\":[]}"));

        String reply = handler.handle("看看我的书架");

        verify(gateway).call(eq("/shelf/sync"), any());
        assertTrue(reply.contains("三体全集"), "应列出书架书名");
        assertTrue(reply.contains("认知觉醒"), "应列出书架书名");
        assertTrue(reply.contains("✅"), "应标记已读完");
    }

    @Test
    void readDataCommandDefaultsToWeekly() throws Exception {
        when(gateway.call(eq("/readdata/detail"), any())).thenReturn(json(
            "{\"readDays\":3,\"totalReadTime\":3600,\"dayAverageReadTime\":1200,"
                + "\"compare\":0.2,"
                + "\"readRate\":70,\"wrReadTime\":2520,\"wrListenTime\":1080,"
                + "\"readStat\":[{\"stat\":\"读过\",\"counts\":\"5本\"},{\"stat\":\"读完\",\"counts\":\"2本\"}],"
                + "\"preferCategoryWord\":\"偏好阅读文学\","
                + "\"preferCategory\":[{\"categoryTitle\":\"文学\"},{\"categoryTitle\":\"历史\"}],"
                + "\"preferTimeWord\":\"偏好夜间阅读\","
                + "\"preferAuthor\":[{\"name\":\"刘慈欣\",\"count\":3,\"readTime\":\"5小时30分钟\"}],"
                + "\"rank\":{\"text\":\"朋友中排第1名\"}}"));

        String reply = handler.handle("这周读了多少");

        verify(gateway).call(eq("/readdata/detail"), any());
        assertTrue(reply.contains("阅读天数"), "应显示阅读天数");
        assertTrue(reply.contains("1 小时"), "3600 秒应格式化为 1 小时");
        assertTrue(reply.contains("20 分钟"), "1200 秒应格式化为 20 分钟（日均）");
        assertTrue(reply.contains("增长20%"), "较上期增长");
        assertTrue(reply.contains("文字阅读占比"), "应显示文字/听书占比");
        assertTrue(reply.contains("偏好阅读文学"), "应显示偏好分类");
        assertTrue(reply.contains("刘慈欣"), "应显示偏好作者");
        assertTrue(reply.contains("偏好夜间阅读"), "应显示偏好时段");
        assertTrue(reply.contains("朋友中排第1名"), "应显示排名");
    }

    @Test
    void readDataCommandDetectsMonthlyMode() throws Exception {
        when(gateway.call(eq("/readdata/detail"), any())).thenReturn(json(
            "{\"readDays\":10,\"totalReadTime\":0}"));

        String reply = handler.handle("这个月读了多少");

        verify(gateway).call(eq("/readdata/detail"), any());
        assertTrue(reply.contains("本月"), "应显示本月口径");
    }

    @Test
    void notebooksCommandShowsOverviewAndMarkedText() throws Exception {
        when(gateway.call(eq("/user/notebooks"), any())).thenReturn(json(
            "{\"totalNoteCount\":5,\"books\":[{"
                + "\"book\":{\"title\":\"认知觉醒\",\"bookId\":\"33628204\"},"
                + "\"noteCount\":2,\"reviewCount\":1,\"bookmarkCount\":1}]}"));
        when(gateway.call(eq("/book/bookmarklist"), any())).thenReturn(json(
            "{\"updated\":[{\"markText\":\"开启自我改变的原动力\"},{\"markText\":\"深度思考\"}]}"));

        String reply = handler.handle("我的划线笔记");

        verify(gateway).call(eq("/user/notebooks"), any());
        verify(gateway).call(eq("/book/bookmarklist"), any());
        assertTrue(reply.contains("认知觉醒"), "应列出有笔记的书");
        assertTrue(reply.contains("5"), "应显示笔记总数");
        assertTrue(reply.contains("开启自我改变的原动力"), "应显示划线内容");
    }

    @Test
    void searchCommandCallsStoreSearch() throws Exception {
        when(gateway.call(eq("/store/search"), any())).thenReturn(json(
            "{\"results\":[{\"books\":["
                + "{\"bookInfo\":{\"title\":\"三体全集（全三册）\",\"author\":\"刘慈欣\","
                + "\"intro\":\"雨果奖获奖作品\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail?type=1&v=abc\"},"
                + "\"newRating\":85,\"newRatingDetail\":{\"title\":\"神作\"}}]}]}"));

        String reply = handler.handle("搜一下 三体");

        verify(gateway).call(eq("/store/search"), argThat(params ->
            "三体".equals(params.get("keyword"))
                && Integer.valueOf(10).equals(params.get("scope"))));
        assertTrue(reply.contains("三体全集"), "应显示搜索到的书名");
        assertTrue(reply.contains("刘慈欣"), "应显示作者");
        assertTrue(reply.contains("⭐4.3"), "评分 85 应对应 4.3 星");
        assertTrue(reply.contains("神作"), "应显示评分标签");
        assertTrue(reply.contains("雨果奖获奖作品"), "应显示简介");
        assertTrue(reply.contains("[打开阅读]"), "链接应为 markdown 格式");
    }

    @Test
    void searchCommandExtractsComplexQuery() throws Exception {
        when(gateway.call(eq("/store/search"), any())).thenReturn(json(
            "{\"results\":[{\"books\":["
                + "{\"bookInfo\":{\"title\":\"心理学与生活\",\"author\":\"理查德·格里格\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail?type=1&v=xyz\"},"
                + "\"newRating\":0}]}]}"));

        String reply = handler.handle("帮我找一下心理学的书");

        verify(gateway).call(eq("/store/search"), argThat(params ->
            "心理学".equals(params.get("keyword"))));
        assertTrue(reply.contains("心理学与生活"), "应搜索并按关键词返回结果");
    }

    @Test
    void genericSearchFallsBackToRecommend() throws Exception {
        when(gateway.call(eq("/book/recommend"), any())).thenReturn(json(
            "{\"books\":[{\"title\":\"认知觉醒\",\"deepLink\":\"https://weread.qq.com/book-detail\"}]}"));

        String reply = handler.handle("找几本书");

        verify(gateway).call(eq("/book/recommend"), argThat(params ->
            params.get("reason") == null));
        assertTrue(reply.contains("🎯"), "应显示推荐标题");
        assertTrue(reply.contains("认知觉醒"), "应显示推荐书名");
    }

    @Test
    void genericRecommendUsesRecommendApi() throws Exception {
        when(gateway.call(eq("/book/recommend"), any())).thenReturn(json(
            "{\"books\":[{\"title\":\"科学脱单指南\",\"author\":\"陈思逸\","
                + "\"intro\":\"一份科学且可操作的方法论\","
                + "\"newRating\":82,\"readingCount\":15600,"
                + "\"reason\":\"符合你的阅读偏好\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail?type=1\"}]}"));

        String reply = handler.handle("推荐几本书");

        // 无具体主题 → /book/recommend（不传 reason，API 不支持）
        verify(gateway).call(eq("/book/recommend"), argThat(params ->
            params.get("reason") == null && Integer.valueOf(3).equals(params.get("count"))));
        assertTrue(reply.contains("科学脱单指南"), "应显示推荐书名");
        assertTrue(reply.contains("⭐4.1"), "评分 82 应对应 4.1 星");
        assertTrue(reply.contains("1.6万"), "15600 应格式化为 1.6万");
        assertTrue(reply.contains("符合你的阅读偏好"), "应显示推荐理由");
        assertTrue(reply.contains("[打开阅读]"), "链接应为 markdown 格式");
    }

    @Test
    void themedRecommendUsesSearchApi() throws Exception {
        when(gateway.call(eq("/store/search"), any())).thenReturn(json(
            "{\"results\":[{\"books\":["
                + "{\"bookInfo\":{\"title\":\"心理学与生活\",\"author\":\"理查德·格里格\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail\"},\"newRating\":0}]}]}"));

        String reply = handler.handle("推荐几本心理学的书");

        // 有具体主题 → /store/search，不调 /book/recommend
        verify(gateway).call(eq("/store/search"), argThat(params ->
            "心理学书籍".equals(params.get("keyword"))));
        assertTrue(reply.contains("主题书单"), "应显示推荐标题");
        assertTrue(reply.contains("心理学与生活"), "应显示推荐书名");
    }

    @Test
    void recommendSciFiNormalizesAndSearches() throws Exception {
        when(gateway.call(eq("/store/search"), any())).thenReturn(json(
            "{\"results\":[{\"books\":["
                + "{\"bookInfo\":{\"title\":\"三体\",\"author\":\"刘慈欣\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail\"},\"newRating\":0}]}]}"));

        String reply = handler.handle("推荐几本科幻风格书");

        // 标准化 "科幻风格" → "科幻小说" → /store/search
        verify(gateway).call(eq("/store/search"), argThat(params ->
            "科幻小说".equals(params.get("keyword"))));
        assertTrue(reply.contains("三体"), "应显示科幻书名");
        assertTrue(reply.contains("科幻小说"), "推荐标题应带标准化关键词");
    }

    @Test
    void unknownInstructionReturnsGuidance() throws Exception {
        String reply = handler.handle("你好");
        assertTrue(reply.contains("未识别"), "未知指令应提示");
    }

    @Test
    void blankInputReturnsGuidance() throws Exception {
        String reply = handler.handle("   ");
        assertTrue(reply.contains("请输入"), "空输入应提示");
    }

    private static JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }
}