package com.clawbot.wechatbot.service.agent;

/** 一次微信消息触发的 Agent 请求身份。 */
public record AgentRequestContext(
    String userId,
    Long messageId
) {
    public AgentRequestContext {
        userId = userId == null ? "" : userId.trim();
    }

    public static AgentRequestContext anonymous() {
        return new AgentRequestContext("", null);
    }

    public boolean hasUser() {
        return !userId.isBlank();
    }
}
