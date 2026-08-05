package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.base.MessageSender;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRecoveryResultDeliveryServiceTests {

    @Test
    void deliversAndMarksRecoveredResultWhenUserSessionIsReady() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        MessageSender sender = mock(MessageSender.class);
        AgentExecutionCheckpoint execution = execution();
        when(sender.isReadyFor("user-1")).thenReturn(true);

        new AgentRecoveryResultDeliveryService(store, sender).deliver(execution);

        verify(sender).sendText(eq("user-1"), contains("恢复结果"));
        verify(store).saveExecution(execution);
        assertTrue(execution.isRecoveryResultDelivered());
    }

    @Test
    void keepsResultPendingUntilWechatSessionIsReady() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        MessageSender sender = mock(MessageSender.class);
        AgentExecutionCheckpoint execution = execution();
        when(sender.isReadyFor("user-1")).thenReturn(false);

        new AgentRecoveryResultDeliveryService(store, sender).deliver(execution);

        verify(sender, never()).sendText(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
        verify(store, never()).saveExecution(execution);
    }

    @Test
    void deliversRecoveryConfirmationPromptAfterWechatReconnects() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        MessageSender sender = mock(MessageSender.class);
        AgentExecutionCheckpoint execution = execution();
        execution.setStatus(AgentCheckpointExecutionStatus.WAITING_CONFIRMATION);
        when(sender.isReadyFor("user-1")).thenReturn(true);

        new AgentRecoveryResultDeliveryService(store, sender)
            .deliverConfirmation(execution);

        verify(sender).sendText(eq("user-1"), contains("回复“确认”"));
        assertTrue(execution.isRecoveryConfirmationNotified());
        verify(store).saveExecution(execution);
    }

    private AgentExecutionCheckpoint execution() {
        AgentExecutionCheckpoint execution = AgentExecutionCheckpoint.create(
            "TASK-1", "user-1", 1L, "测试", Instant.now());
        execution.setRecoveryCompletedAt(Instant.now());
        execution.setRecoveryResultText("恢复结果");
        return execution;
    }
}
