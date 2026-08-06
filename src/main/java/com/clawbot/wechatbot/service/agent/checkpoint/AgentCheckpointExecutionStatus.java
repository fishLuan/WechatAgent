package com.clawbot.wechatbot.service.agent.checkpoint;

/** Persisted lifecycle used by checkpoint recovery, independent from runtime audit status. */
public enum AgentCheckpointExecutionStatus {
    CREATED,
    PLANNING,
    RUNNING,
    WAITING_CONFIRMATION,
    RETRY_WAITING,
    REPLANNING,
    RECOVERING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PARTIALLY_CANCELLED;

    public boolean recoverable() {
        return switch (this) {
            case PLANNING, RUNNING, WAITING_CONFIRMATION,
                 RETRY_WAITING, REPLANNING, RECOVERING -> true;
            default -> false;
        };
    }
}
