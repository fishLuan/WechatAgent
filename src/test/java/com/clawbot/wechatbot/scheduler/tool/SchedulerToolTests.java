package com.clawbot.wechatbot.scheduler.tool;

import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerToolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchedulerControlService controlService =
        mock(SchedulerControlService.class);
    private final AgentRequestContextHolder requestContextHolder =
        new AgentRequestContextHolder();
    private final SchedulerTool tool =
        new SchedulerTool(controlService, mapper, requestContextHolder);

    @Test
    void rejectsExecutionWithoutRequestContext() throws Exception {
        JsonNode result = mapper.readTree(tool.execute(mapper.readTree("""
            {"action":"list_subscriptions"}
            """)));

        assertFalse(result.path("success").asBoolean());
    }

    @Test
    void usesBoundWechatUserAndIgnoresModelSuppliedUserId() throws Exception {
        when(controlService.listByUser("wechat-user")).thenReturn(List.of());

        JsonNode result;
        try (AgentRequestContextHolder.Scope ignored = requestContextHolder.open(
            new AgentRequestContext("wechat-user", 2L))) {
            result = mapper.readTree(tool.execute(mapper.readTree("""
                {
                  "action":"list_subscriptions",
                  "user_id":"attacker"
                }
                """)));
        }

        assertTrue(result.path("success").asBoolean());
        verify(controlService).listByUser("wechat-user");
        verify(controlService, never()).listByUser("attacker");
    }
}
