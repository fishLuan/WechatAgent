package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
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
    private final AgentRequestContextHolder requestContextHolder =
        new AgentRequestContextHolder();
    private final BilibiliTool tool =
        new BilibiliTool(commandHandler, mapper, requestContextHolder);

    @Test
    void reportsBusinessFailureAsUnsuccessfulToolResult() throws Exception {
        when(commandHandler.handleMarkState(
            "wechat-user", null, "watched"))
            .thenReturn("❌ 要标记的编号不对");

        JsonNode result = executeAs("wechat-user", """
                {"action":"mark_watched"}
                """);

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("reply_text").asText().contains("编号不对"));
    }

    @Test
    void marksContentByTitleThroughDedicatedAction() throws Exception {
        when(commandHandler.handleMarkStateByTitle(
            "wechat-user", "航海王：红发歌姬", "watched"))
            .thenReturn("✅ 已标记为看过");

        JsonNode result = executeAs("wechat-user", """
                {
                  "action":"mark_watched_by_title",
                  "title":"航海王：红发歌姬"
                }
                """);

        assertTrue(result.path("success").asBoolean());
    }

    @Test
    void rejectsUnknownActionWithoutNestedJsonReply() throws Exception {
        JsonNode result = executeAs("wechat-user", """
                {"action":"does_not_exist"}
                """);

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("reply_text").asText().contains("未知操作类型"));
    }

    @Test
    void excludesWeekdaysThroughFunctionCalling() throws Exception {
        when(commandHandler.handleWeekdayPushPolicy(
            "wechat-user", null, Set.of(DayOfWeek.SATURDAY), true))
            .thenReturn("✅ 已设置周六不发送每日推荐。");

        JsonNode result = executeAs("wechat-user", """
                {
                  "action":"exclude_push_days",
                  "weekdays":["saturday"]
                }
                """);

        assertTrue(result.path("success").asBoolean());
    }

    @Test
    void rejectsExecutionWithoutRequestContext() throws Exception {
        JsonNode result = mapper.readTree(tool.execute(
            mapper.readTree("""
                {"action":"list_subscriptions"}
                """)));

        assertFalse(result.path("success").asBoolean());
    }

    private JsonNode executeAs(String userId, String arguments) throws Exception {
        try (AgentRequestContextHolder.Scope ignored = requestContextHolder.open(
            new AgentRequestContext(userId, 1L))) {
            return mapper.readTree(tool.execute(mapper.readTree(arguments)));
        }
    }
}
