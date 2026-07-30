package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 将一条用户消息规划为带类型和依赖关系的结构化任务。 */
public interface TaskPlanner {
    List<AgentTask> plan(String userText) throws Exception;

    default boolean isConfigured() {
        return true;
    }
}
