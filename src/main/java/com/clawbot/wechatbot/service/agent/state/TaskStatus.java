package com.clawbot.wechatbot.service.agent.state;

/** 外层任务从等待执行到最终完成的生命周期状态。 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    VERIFYING,
    VERIFIED,
    RETRY_PENDING,
    REPLAN_REQUIRED,
    FAILED,
    ABORTED,
    CANCELLED;

    public boolean terminal() {
        return this == VERIFIED || this == FAILED || this == ABORTED || this == CANCELLED;
    }
}
