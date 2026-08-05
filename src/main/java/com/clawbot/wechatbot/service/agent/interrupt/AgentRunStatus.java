package com.clawbot.wechatbot.service.agent.interrupt;

public enum AgentRunStatus {
    RUNNING, CANCEL_REQUESTED, CANCELLING, CANCELLED, PARTIALLY_CANCELLED,
    SUCCEEDED, FAILED
}
