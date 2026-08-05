package com.clawbot.wechatbot.confirmation;

import com.clawbot.wechatbot.base.MessageSender;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.clawbot.wechatbot.service.agent.AgentResponse;
import com.clawbot.wechatbot.service.agent.checkpoint.AgentCheckpointRecoveryRunner;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmationReplyServiceRecoveryTests {

    @Test
    void confirmedRecoveryResumesCheckpointInsteadOfCallingToolRegistry()
        throws Exception {
        ConfirmationService confirmations = mock(ConfirmationService.class);
        AgentCheckpointRecoveryRunner recovery =
            mock(AgentCheckpointRecoveryRunner.class);
        PendingConfirmation pending = recoveryConfirmation();
        when(confirmations.waiting("user-1")).thenReturn(List.of(pending));
        when(recovery.resumeConfirmed("TASK-1"))
            .thenReturn(AgentResponse.text("任务完成"));
        ConfirmationReplyService service = service(confirmations, recovery);

        ConfirmationReply reply = service.handle("user-1", 2L, "确认");

        assertTrue(reply.handled());
        assertTrue(reply.message().contains("任务完成"));
        verify(recovery).resumeConfirmed("TASK-1");
    }

    @Test
    void cancelledRecoveryTerminatesCheckpoint() throws Exception {
        ConfirmationService confirmations = mock(ConfirmationService.class);
        AgentCheckpointRecoveryRunner recovery =
            mock(AgentCheckpointRecoveryRunner.class);
        PendingConfirmation pending = recoveryConfirmation();
        when(confirmations.waiting("user-1")).thenReturn(List.of(pending));
        ConfirmationReplyService service = service(confirmations, recovery);

        ConfirmationReply reply = service.handle("user-1", 2L, "取消");

        assertTrue(reply.handled());
        verify(recovery).cancelRecovery("TASK-1");
    }

    private ConfirmationReplyService service(
        ConfirmationService confirmations,
        AgentCheckpointRecoveryRunner recovery
    ) {
        return new ConfirmationReplyService(confirmations,
            mock(FunctionToolRegistry.class), new AgentRequestContextHolder(),
            new ObjectMapper(), recovery, mock(MessageSender.class));
    }

    private PendingConfirmation recoveryConfirmation() {
        PendingConfirmation pending = new PendingConfirmation();
        pending.setId("CFM-12345678");
        pending.setUserId("user-1");
        pending.setToolName("__agent_checkpoint_recovery__");
        pending.setArgumentsJson("{\"execution_id\":\"TASK-1\"}");
        pending.setOperationSummary("恢复任务");
        pending.setStatus(ConfirmationStatus.WAITING_CONFIRMATION);
        return pending;
    }
}
