package com.clawbot.wechatbot.scheduler.validation;

import com.clawbot.wechatbot.service.agent.validation.ToolValidationContext;
import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerToolResultValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchedulerToolResultValidator validator =
        new SchedulerToolResultValidator(mapper);

    @Test
    void acceptsWaitingConfirmationWithoutCancelCount() throws Exception {
        var context = new ToolValidationContext("取消全部定时订阅", "scheduler_manage",
            mapper.readTree("""
                {"action":"cancel_subscription","cancel_all":true}
                """),
            new ToolExecutionOutcome("""
                {"success":true,"confirmation_required":true,
                 "execution_status":"WAITING_CONFIRMATION","confirmation_id":"CFM-12345678"}
                """, true, false, "CONFIRMATION_REQUIRED"), Map.of());

        assertTrue(validator.validate(context).passed());
    }

    @Test
    void stillRejectsCompletedBulkCancelWithoutCount() throws Exception {
        var context = new ToolValidationContext("取消全部定时订阅", "scheduler_manage",
            mapper.readTree("""
                {"action":"cancel_subscription","cancel_all":true}
                """),
            new ToolExecutionOutcome("""
                {"success":true,"execution_status":"SUCCEEDED","message":"已取消"}
                """, true, false, ""), Map.of());

        assertFalse(validator.validate(context).passed());
    }
}
