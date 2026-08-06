package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentTask;

import java.util.List;
import java.util.Optional;
import java.time.Duration;

/** Persistence boundary used by later orchestration and recovery stages. */
public interface AgentCheckpointStore {
    AgentExecutionCheckpoint createExecution(
        String executionId,
        String userId,
        Long sourceMessageId,
        String originalRequest
    );

    AgentExecutionCheckpoint saveExecution(AgentExecutionCheckpoint execution);

    AgentExecutionSnapshot savePlan(
        String executionId, int planVersion, List<AgentTask> tasks);

    AgentTaskCheckpoint saveTask(AgentTaskCheckpoint checkpoint);

    Optional<AgentExecutionSnapshot> load(String executionId);

    List<AgentExecutionSnapshot> findRecoverableExecutions();

    boolean tryAcquireRecoveryLease(
        String executionId, String owner, Duration duration);

    void releaseRecoveryLease(String executionId, String owner);

    List<AgentExecutionCheckpoint> findUndeliveredRecoveryResults();

    List<AgentExecutionCheckpoint> findUnnotifiedRecoveryConfirmations();

    AgentTask deserializeTask(AgentTaskCheckpoint checkpoint);
}
