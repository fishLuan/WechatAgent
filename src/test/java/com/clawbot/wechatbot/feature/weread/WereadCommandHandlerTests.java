package com.clawbot.wechatbot.feature.weread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        assertTrue(reply.contains("已读完"), "应标记已读完");
    }

    @Test
    void readDataCommandDefaultsToWeekly() throws Exception {
        when(gateway.call(eq("/readdata/detail"), any())).thenReturn(json(
            "{\"readDays\":3,\"totalReadTime\":3600,"
                + "\"rank\":{\"text\":\"朋友中排第1名\"},"
                + "\"preferBooks\":[{\"title\":\"我的最爱\"},{\"title\":\"读到深夜\"}]}"));

        String reply = handler.handle("这周读了多少");

        verify(gateway).call(eq("/readdata/detail"), any());
        assertTrue(reply.contains("阅读天数"), "应显示阅读天数");
        assertTrue(reply.contains("1 小时"), "3600 秒应格式化为 1 小时");
        assertTrue(reply.contains("朋友中排第1名"), "应显示排名");
        assertTrue(reply.contains("我的最爱"), "应显示偏好标签");
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
    void searchCommandExtractsKeywordAndShowsResults() throws Exception {
        when(gateway.call(eq("/store/search"), any())).thenReturn(json(
            "{\"results\":[{\"books\":[{\"bookInfo\":{"
                + "\"title\":\"三体全集（全三册）\",\"author\":\"刘慈欣\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail?type=1\"}}]}]}"));

        String reply = handler.handle("搜一下 三体");

        verify(gateway).call(eq("/store/search"), any());
        assertTrue(reply.contains("三体全集"), "应显示搜索结果书名");
        assertTrue(reply.contains("刘慈欣"), "应显示作者");
    }

    @Test
    void recommendCommandCallsRecommendApi() throws Exception {
        when(gateway.call(eq("/book/recommend"), any())).thenReturn(json(
            "{\"books\":[{\"title\":\"科学脱单指南\",\"author\":\"陈思逸\","
                + "\"category\":\"心理-亲密关系\","
                + "\"intro\":\"一份科学且可操作的方法论\","
                + "\"deepLink\":\"https://weread.qq.com/book-detail?type=1\"}]}"));

        String reply = handler.handle("推荐几本书");

        verify(gateway).call(eq("/book/recommend"), any());
        assertTrue(reply.contains("科学脱单指南"), "应显示推荐书名");
        assertTrue(reply.contains("心理-亲密关系"), "应显示分类");
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
