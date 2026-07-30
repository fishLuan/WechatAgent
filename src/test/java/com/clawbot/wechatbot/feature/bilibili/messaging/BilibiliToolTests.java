package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BilibiliToolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BilibiliCommandHandler commandHandler =
        mock(BilibiliCommandHandler.class);
    private final BilibiliTool tool =
        new BilibiliTool(commandHandler, mapper);

    @AfterEach
    void clearCurrentUser() {
        BilibiliTool.CURRENT_USER_ID.remove();
    }

    @Test
    void reportsBusinessFailureAsUnsuccessfulToolResult() throws Exception {
        BilibiliTool.CURRENT_USER_ID.set("wechat-user");
        when(commandHandler.handleMarkState(
            "wechat-user", null, "watched"))
            .thenReturn("❌ 要标记的编号不对");

        JsonNode result = mapper.readTree(tool.execute(
            mapper.readTree("""
                {"action":"mark_watched"}
                """)));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("reply_text").asText().contains("编号不对"));
    }

    @Test
    void marksContentByTitleThroughDedicatedAction() throws Exception {
        BilibiliTool.CURRENT_USER_ID.set("wechat-user");
        when(commandHandler.handleMarkStateByTitle(
            "wechat-user", "航海王：红发歌姬", "watched"))
            .thenReturn("✅ 已标记为看过");

        JsonNode result = mapper.readTree(tool.execute(
            mapper.readTree("""
                {
                  "action":"mark_watched_by_title",
                  "title":"航海王：红发歌姬"
                }
                """)));

        assertTrue(result.path("success").asBoolean());
    }

    @Test
    void rejectsUnknownActionWithoutNestedJsonReply() throws Exception {
        BilibiliTool.CURRENT_USER_ID.set("wechat-user");

        JsonNode result = mapper.readTree(tool.execute(
            mapper.readTree("""
                {"action":"does_not_exist"}
                """)));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("reply_text").asText().contains("未知操作类型"));
    }

    @Test
    void excludesWeekdaysThroughFunctionCalling() throws Exception {
        BilibiliTool.CURRENT_USER_ID.set("wechat-user");
        when(commandHandler.handleWeekdayPushPolicy(
            "wechat-user", null, Set.of(DayOfWeek.SATURDAY), true))
            .thenReturn("✅ 已设置周六不发送每日推荐。");

        JsonNode result = mapper.readTree(tool.execute(
            mapper.readTree("""
                {
                  "action":"exclude_push_days",
                  "weekdays":["saturday"]
                }
                """)));

        assertTrue(result.path("success").asBoolean());
    }
}
