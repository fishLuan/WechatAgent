package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.AgentTask;

/** 一项受控的任务图修改。 */
public record PlanMutation(
    PlanMutationType type,
    String targetTaskId,
    AgentTask task,
    String reason
) {
    public PlanMutation {
        if (type == null) throw new IllegalArgumentException("变更类型不能为空");
        if (targetTaskId == null || targetTaskId.isBlank()) {
            throw new IllegalArgumentException("目标任务 ID 不能为空");
        }
        targetTaskId = targetTaskId.trim();
        reason = reason == null ? "" : reason.trim();
        if ((type == PlanMutationType.REPLACE_TASK
            || type == PlanMutationType.INSERT_BEFORE) && task == null) {
            throw new IllegalArgumentException(type + " 必须提供新任务");
        }
        if ((type == PlanMutationType.RETRY_TASK
            || type == PlanMutationType.ABORT_BRANCH) && task != null) {
            throw new IllegalArgumentException(type + " 不应提供新任务");
        }
    }
}
