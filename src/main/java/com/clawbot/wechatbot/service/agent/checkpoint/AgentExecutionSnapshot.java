package com.clawbot.wechatbot.service.agent.checkpoint;

import java.util.List;

public record AgentExecutionSnapshot(
    AgentExecutionCheckpoint execution,
    List<AgentTaskCheckpoint> tasks
) {
    public AgentExecutionSnapshot {
        if (execution == null) throw new IllegalArgumentException("execution不能为空");
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
