package com.clawbot.wechatbot.service.agent.validation;

/** 工具结果验收结论。 */
public record ToolValidationResult(
    ToolValidationAction action,
    double confidence,
    String code,
    String reason,
    String correctiveAction
) {
    public ToolValidationResult {
        action = action == null ? ToolValidationAction.ABORT : action;
        confidence = Math.max(0D, Math.min(1D, confidence));
        code = code == null ? "" : code;
        reason = reason == null ? "" : reason;
        correctiveAction = correctiveAction == null ? "" : correctiveAction;
    }

    public static ToolValidationResult pass(double confidence) {
        return new ToolValidationResult(
            ToolValidationAction.PASS, confidence, "", "", "");
    }

    public boolean passed() {
        return action == ToolValidationAction.PASS;
    }
}
