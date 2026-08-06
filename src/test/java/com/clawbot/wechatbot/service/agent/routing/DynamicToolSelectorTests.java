package com.clawbot.wechatbot.service.agent.routing;

import com.clawbot.wechatbot.intent.IntentResult;
import com.clawbot.wechatbot.intent.IntentType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicToolSelectorTests {

    private final DynamicToolSelector selector = new DynamicToolSelector();

    @Test
    void routesGeneralChatWithoutTools() {
        DynamicToolSelector.FastRoute route = selector.fastRoute(
            new IntentResult(IntentType.GENERAL_CHAT, 0.60, Map.of()),
            "你好，介绍一下责任链模式").orElseThrow();

        assertTrue(route.allowedTools().isEmpty());
        assertEquals("general-chat", route.reason());
    }

    @Test
    void domainPreferenceStatementsRemainGeneralChat() {
        for (String text : Set.of(
            "我在上海，我喜欢看动漫",
            "我比较喜欢科幻电影",
            "我平时喜欢读小说",
            "我工作中经常使用Excel",
            "我喜欢听男声")) {
            DynamicToolSelector.FastRoute route = selector.fastRoute(
                new IntentResult(IntentType.GENERAL_CHAT, 0.60, Map.of()),
                text).orElseThrow();
            assertTrue(route.allowedTools().isEmpty(), text);
            assertEquals("general-chat", route.reason(), text);
        }
    }

    @Test
    void explicitDomainActionsStillUsePlanner() {
        for (String text : Set.of(
            "推荐三部动漫",
            "搜索一部科幻电影",
            "给我推荐三本书",
            "把新闻生成Excel表格",
            "用男声回复我")) {
            assertTrue(selector.fastRoute(
                new IntentResult(IntentType.GENERAL_CHAT, 0.60, Map.of()),
                text).isEmpty(), text);
        }
    }

    @Test
    void routesExplicitWeatherQueryWithOnlyWeatherTool() {
        DynamicToolSelector.FastRoute route = selector.fastRoute(
            new IntentResult(IntentType.TOOL_QUERY, 0.88, Map.of()),
            "查询今天杭州的天气").orElseThrow();

        assertEquals(Set.of("get_weather"), route.allowedTools());
    }

    @Test
    void keepsMultipleToolsOnPlannerPath() {
        assertTrue(selector.fastRoute(
            new IntentResult(IntentType.MULTI_TASK, 0.90, Map.of()),
            "查询北京天气，然后规划杭州到北京的路线").isEmpty());
    }

    @Test
    void keepsVoiceAndImageRequestsOnSkillPlannerPath() {
        assertTrue(selector.fastRoute(
            new IntentResult(IntentType.TOOL_QUERY, 0.88, Map.of()),
            "用语音回复杭州天气").isEmpty());
        assertTrue(selector.fastRoute(
            new IntentResult(IntentType.IMAGE_GENERATION, 0.95, Map.of()),
            "生成一张小猫图片").isEmpty());
    }

    @Test
    void selectsToolsPerAgentTask() {
        assertEquals(
            Set.of("get_route_plan"),
            selector.toolsForTask("规划杭州到北京的路线").orElseThrow());
    }
}
