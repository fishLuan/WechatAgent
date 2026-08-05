package com.clawbot.wechatbot.service.agent.replan;

/** 根据失败步骤和可信结果生成局部计划修改。 */
public interface TaskReplanner {
    ReplanResult replan(ReplanRequest request) throws Exception;

    boolean isConfigured();
}
