package com.clawbot.wechatbot.service.agent.acceptance;

/** 外层任务执行后的验收决策。 */
public enum TaskDecision {
    PASS,
    RETRY,
    REPLAN,
    ABORT
}
