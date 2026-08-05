package com.clawbot.wechatbot.service.agent.validation;

/**
 * 可扩展的工具结果验收器。关键业务工具可提供专属实现；通用校验器负责兜底。
 */
public interface ToolResultValidator {
    boolean supports(String toolName);

    default int order() {
        return 100;
    }

    ToolValidationResult validate(ToolValidationContext context);
}
