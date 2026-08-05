package com.clawbot.wechatbot.service.agent.interrupt;

public record CancelResult(boolean found, String executionId, AgentRunStatus status) {
}
