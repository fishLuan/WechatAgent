package com.clawbot.wechatbot.service.agent.replan;

/** 局部重规划器唯一允许生成的任务图修改类型。 */
public enum PlanMutationType {
    RETRY_TASK,
    REPLACE_TASK,
    INSERT_BEFORE,
    ABORT_BRANCH
}
