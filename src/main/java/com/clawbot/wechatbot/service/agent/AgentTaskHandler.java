package com.clawbot.wechatbot.service.agent;

/** 外循环中的任务处理器，与微信消息类型 Handler 相互独立。 */
public interface AgentTaskHandler {
    boolean supports(AgentTaskType type);

    AgentTaskResult execute(AgentTask task, AgentTaskContext context) throws Exception;
}
