package com.clawbot.wechatbot.service.agent.checkpoint;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AgentTaskCheckpointRepository
    extends MongoRepository<AgentTaskCheckpoint, String> {

    List<AgentTaskCheckpoint> findByExecutionIdOrderByOrderAsc(String executionId);
}
