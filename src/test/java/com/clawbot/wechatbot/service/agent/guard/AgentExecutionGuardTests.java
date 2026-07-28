package com.clawbot.wechatbot.service.agent.guard;

import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionGuardTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowsTwoChatLevelsButRejectsTheThird() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofSeconds(5));

        try (AgentExecutionGuard.ChatScope first = guard.enterChat();
             AgentExecutionGuard.ChatScope second = guard.enterChat()) {
            AgentSafetyException error = assertThrows(
                AgentSafetyException.class, guard::enterChat);
            assertEquals("CHAT_DEPTH_EXCEEDED", error.code());
        }

        assertDoesNotThrow(() -> {
            try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
                // 根调用结束后深度必须正确清理。
            }
        });
    }

    @Test
    void blocksSameToolAndCanonicalArgumentsAfterSuccessfulResult() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofSeconds(5));
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            AgentExecutionGuard.ToolCallDecision first = guard.beforeTool(
                "get_weather", "{\"city\":\"北京\",\"extensions\":\"base\"}");
            guard.completeTool(
                first,
                new ToolExecutionOutcome(
                    "{\"success\":true,\"weather\":\"晴\"}", true, false, ""));

            AgentExecutionGuard.ToolCallDecision repeated = guard.beforeTool(
                "get_weather", "{\"extensions\":\"base\",\"city\":\"北京\"}");

            assertFalse(repeated.execute());
            assertTrue(repeated.blockedOutcome().content()
                .contains("DUPLICATE_TOOL_CALL"));
            assertTrue(guard.forceFinalResponse());
        }
    }

    @Test
    void blocksToolWhenItReappearsInTheActiveNestedCallPath() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofSeconds(5));
        try (AgentExecutionGuard.ChatScope root = guard.enterChat()) {
            AgentExecutionGuard.ToolCallDecision outer =
                guard.beforeTool("summarize_history", "{}");
            try (AgentExecutionGuard.ChatScope nested = guard.enterChat()) {
                AgentExecutionGuard.ToolCallDecision recursive =
                    guard.beforeTool("summarize_history", "{}");

                assertFalse(recursive.execute());
                assertTrue(recursive.blockedOutcome().content()
                    .contains("RECURSIVE_TOOL_CALL"));
            } finally {
                guard.abortTool(outer);
            }
        }
    }

    @Test
    void allowsSameToolWithDifferentArguments() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofSeconds(5));
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            AgentExecutionGuard.ToolCallDecision beijing =
                guard.beforeTool("get_weather", "{\"city\":\"北京\"}");
            guard.completeTool(
                beijing, new ToolExecutionOutcome("北京晴", true, false, ""));

            AgentExecutionGuard.ToolCallDecision shanghai =
                guard.beforeTool("get_weather", "{\"city\":\"上海\"}");

            assertTrue(shanghai.execute());
            guard.abortTool(shanghai);
        }
    }

    @Test
    void opensCircuitAfterSameToolFailsTwice() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofSeconds(5));
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                AgentExecutionGuard.ToolCallDecision decision =
                    guard.beforeTool("get_weather", "{\"attempt\":" + attempt + "}");
                guard.completeTool(
                    decision,
                    new ToolExecutionOutcome(
                        "{\"success\":false}", false, true, "FAILED"));
            }

            assertTrue(guard.circuitOpenTools().contains("get_weather"));
            AgentExecutionGuard.ToolCallDecision blocked =
                guard.beforeTool("get_weather", "{\"attempt\":3}");
            assertFalse(blocked.execute());
            assertTrue(blocked.blockedOutcome().content().contains("TOOL_CIRCUIT_OPEN"));
        }
    }

    @Test
    void returnsValidTruncatedJsonForOversizedSingleResult() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofSeconds(5));
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            AgentExecutionGuard.ToolCallDecision decision =
                guard.beforeTool("large_tool", "{}");
            String result = guard.completeTool(
                decision,
                new ToolExecutionOutcome("汉".repeat(2000), true, false, ""));
            JsonNode parsed = mapper.readTree(result);

            assertTrue(result.length() <= 512);
            assertTrue(parsed.path("truncated").asBoolean());
            assertEquals(2000, parsed.path("originalChars").asInt());
        }
    }

    @Test
    void stopsToolsWhenCumulativeResultBudgetWouldBeExceeded() throws Exception {
        AgentExecutionGuard guard = guard(512, 700, Duration.ofSeconds(5));
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            AgentExecutionGuard.ToolCallDecision first =
                guard.beforeTool("tool_a", "{}");
            guard.completeTool(
                first, new ToolExecutionOutcome("a".repeat(400), true, false, ""));

            AgentExecutionGuard.ToolCallDecision second =
                guard.beforeTool("tool_b", "{}");
            String result = guard.completeTool(
                second, new ToolExecutionOutcome("b".repeat(400), true, false, ""));

            assertTrue(result.contains("TOOL_CONTENT_BUDGET_EXCEEDED"));
            assertTrue(guard.forceFinalResponse());
        }
    }

    @Test
    void enforcesPerRoundAndTotalToolCallLimits() throws Exception {
        AgentGuardPolicy policy = new AgentGuardPolicy(
            2, 2, 2, 2, 512, 2048, Duration.ofSeconds(5));
        AgentExecutionGuard guard = new AgentExecutionGuard(policy, mapper);
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            assertThrows(
                AgentSafetyException.class,
                () -> guard.validateRoundToolCallCount(3));

            for (int call = 0; call < 2; call++) {
                AgentExecutionGuard.ToolCallDecision decision =
                    guard.beforeTool("tool_" + call, "{}");
                guard.completeTool(
                    decision, new ToolExecutionOutcome("ok", true, false, ""));
            }
            assertTrue(guard.forceFinalResponse());
            AgentExecutionGuard.ToolCallDecision blocked =
                guard.beforeTool("tool_3", "{}");
            assertFalse(blocked.execute());
            assertTrue(blocked.blockedOutcome().content()
                .contains("TOTAL_TOOL_CALL_LIMIT_EXCEEDED"));
        }
    }

    @Test
    void rejectsExecutionAfterDeadline() throws Exception {
        AgentExecutionGuard guard = guard(512, 2048, Duration.ofMillis(10));
        try (AgentExecutionGuard.ChatScope ignored = guard.enterChat()) {
            Thread.sleep(30);
            AgentSafetyException error = assertThrows(
                AgentSafetyException.class, guard::checkDeadline);
            assertEquals("AGENT_EXECUTION_TIMEOUT", error.code());
        }
    }

    private AgentExecutionGuard guard(
        int maxResultChars, int maxTotalChars, Duration timeout
    ) {
        return new AgentExecutionGuard(
            new AgentGuardPolicy(
                2, 4, 8, 2, maxResultChars, maxTotalChars, timeout),
            mapper);
    }
}
