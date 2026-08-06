package com.clawbot.wechatbot.service.agent.replan;

import java.util.List;

/** 计划变更应用前的完整校验结果。 */
public record PlanMutationValidationResult(boolean valid, List<String> errors) {
    public PlanMutationValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static PlanMutationValidationResult success() {
        return new PlanMutationValidationResult(true, List.of());
    }
}
