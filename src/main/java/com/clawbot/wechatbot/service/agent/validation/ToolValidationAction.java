package com.clawbot.wechatbot.service.agent.validation;

/** 工具结果未通过验收时，内层 Agent 应采取的动作。 */
public enum ToolValidationAction {
    PASS,
    RETRY,
    REPLAN,
    ABORT
}
