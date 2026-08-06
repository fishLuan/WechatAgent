package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;

/** 校验通过后将一组局部变更应用到执行状态。 */
public final class PlanMutationApplier {
    private final PlanMutationValidator validator;

    public PlanMutationApplier(PlanMutationValidator validator) {
        this.validator = validator;
    }

    public void apply(AgentExecutionState state, ReplanResult result) {
        PlanMutationValidationResult validation = validator.validate(state, result);
        if (!validation.valid()) {
            throw new IllegalArgumentException(
                "重规划结果不合法：" + String.join("；", validation.errors()));
        }
        for (PlanMutation mutation : result.mutations()) {
            switch (mutation.type()) {
                case RETRY_TASK -> state.scheduleRetry(mutation.targetTaskId());
                case REPLACE_TASK -> state.replaceTask(
                    mutation.targetTaskId(), mutation.task());
                case INSERT_BEFORE -> state.insertTaskBefore(
                    mutation.targetTaskId(), mutation.task());
                case ABORT_BRANCH -> state.abortBranch(
                    mutation.targetTaskId(), mutation.reason().isBlank()
                        ? "重规划决定终止该任务分支" : mutation.reason());
            }
        }
        // 只有重规划模型产出的修改才通过本应用器；普通自动重试直接由状态机调度。
        state.incrementReplanCount();
    }
}
