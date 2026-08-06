package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentOrchestrator;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointRecoveryRunnerTests {

    @Test
    void resumesSafeInterruptedExecution() throws Exception {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentExecutionSnapshot snapshot = snapshot(false, TaskStatus.RUNNING);
        when(store.tryAcquireRecoveryLease(anyString(), anyString(), any()))
            .thenReturn(true);
        when(store.load("TASK-1")).thenReturn(Optional.of(snapshot));

        new AgentCheckpointRecoveryRunner(store, orchestrator).recover(snapshot);

        verify(orchestrator).resume(snapshot);
        verify(store).releaseRecoveryLease(anyString(), anyString());
    }

    @Test
    void pausesUncertainSideEffectInsteadOfRepeatingIt() throws Exception {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentExecutionSnapshot snapshot = snapshot(true, TaskStatus.RUNNING);
        when(store.tryAcquireRecoveryLease(anyString(), anyString(), any()))
            .thenReturn(true);
        when(store.load("TASK-1")).thenReturn(Optional.of(snapshot));
        when(store.saveExecution(any())).thenAnswer(call -> call.getArgument(0));

        new AgentCheckpointRecoveryRunner(store, orchestrator).recover(snapshot);

        assertEquals(AgentCheckpointExecutionStatus.WAITING_CONFIRMATION,
            snapshot.execution().getStatus());
        assertEquals("RECOVERY_SIDE_EFFECT_UNCERTAIN",
            snapshot.execution().getFailureCode());
        verify(orchestrator, never()).resume(any());
    }

    private AgentExecutionSnapshot snapshot(boolean sideEffect, TaskStatus status) {
        AgentExecutionCheckpoint execution = AgentExecutionCheckpoint.create(
            "TASK-1", "user-1", 1L, "测试恢复", Instant.now());
        execution.setStatus(AgentCheckpointExecutionStatus.RUNNING);
        AgentTask task = new AgentTask("task-1", 0,
            AgentTaskType.SKILL, "demo", "执行任务", List.of());
        AgentTaskCheckpoint checkpoint = AgentTaskCheckpoint.fromTask(
            "TASK-1", 1, task, "{}", Instant.now());
        checkpoint.setStatus(status);
        checkpoint.setSideEffect(sideEffect);
        return new AgentExecutionSnapshot(execution, List.of(checkpoint));
    }
}
