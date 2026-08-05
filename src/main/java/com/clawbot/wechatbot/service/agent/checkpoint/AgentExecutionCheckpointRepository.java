package com.clawbot.wechatbot.service.agent.checkpoint;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface AgentExecutionCheckpointRepository
    extends MongoRepository<AgentExecutionCheckpoint, String> {

    List<AgentExecutionCheckpoint> findByStatusInOrderByUpdatedAtAsc(
        Collection<AgentCheckpointExecutionStatus> statuses);

    List<AgentExecutionCheckpoint>
        findByRecoveryCompletedAtNotNullAndRecoveryResultDeliveredFalseOrderByRecoveryCompletedAtAsc();

    List<AgentExecutionCheckpoint>
        findByStatusAndRecoveryConfirmationNotifiedFalseOrderByUpdatedAtAsc(
            AgentCheckpointExecutionStatus status);
}
