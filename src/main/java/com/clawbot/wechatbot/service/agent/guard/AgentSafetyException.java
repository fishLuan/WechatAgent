package com.clawbot.wechatbot.service.agent.guard;

/** Agent 安全预算或调用策略被突破时抛出的可控异常。 */
public final class AgentSafetyException extends Exception {
    private final String code;

    public AgentSafetyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
