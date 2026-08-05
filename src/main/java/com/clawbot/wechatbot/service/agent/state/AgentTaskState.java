package com.clawbot.wechatbot.service.agent.state;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 单个任务的运行状态、执行次数和最近一次验收结论。 */
public final class AgentTaskState {
    private AgentTask task;
    private TaskStatus status = TaskStatus.PENDING;
    private int attemptCount;
    private int replanGeneration;
    private AgentTaskResult lastResult;
    private TaskEvaluation lastEvaluation;
    private JsonNode verifiedOutput = JsonNodeFactory.instance.objectNode();

    AgentTaskState(AgentTask task) {
        this.task = task;
    }

    public AgentTask task() {
        return task;
    }

    public TaskStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public int replanGeneration() {
        return replanGeneration;
    }

    public AgentTaskResult lastResult() {
        return lastResult;
    }

    public TaskEvaluation lastEvaluation() {
        return lastEvaluation;
    }

    public JsonNode verifiedOutput() {
        return verifiedOutput.deepCopy();
    }

    void markRunning() {
        requireStatus(TaskStatus.PENDING, TaskStatus.RETRY_PENDING);
        status = TaskStatus.RUNNING;
        attemptCount++;
    }

    void markVerifying(AgentTaskResult result) {
        requireStatus(TaskStatus.RUNNING);
        lastResult = result;
        status = TaskStatus.VERIFYING;
    }

    void applyEvaluation(TaskEvaluation evaluation) {
        requireStatus(TaskStatus.VERIFYING);
        lastEvaluation = evaluation;
        status = switch (evaluation.decision()) {
            case PASS -> TaskStatus.VERIFIED;
            case RETRY -> TaskStatus.RETRY_PENDING;
            case REPLAN -> TaskStatus.REPLAN_REQUIRED;
            case ABORT -> TaskStatus.ABORTED;
        };
        if (status == TaskStatus.VERIFIED) {
            verifiedOutput = evaluation.verifiedOutput();
        }
    }

    void markFailed(String reason) {
        if (status == TaskStatus.VERIFIED) {
            throw new IllegalStateException("已验收任务不能改为失败：" + task.id());
        }
        lastResult = AgentTaskResult.failure(task, reason);
        status = TaskStatus.FAILED;
    }

    void incrementReplanGeneration() {
        replanGeneration++;
    }

    void scheduleRetry() {
        requireStatus(TaskStatus.RETRY_PENDING, TaskStatus.REPLAN_REQUIRED);
        status = TaskStatus.PENDING;
    }

    void replaceTask(AgentTask replacement) {
        if (status == TaskStatus.VERIFIED || status == TaskStatus.RUNNING
            || status == TaskStatus.VERIFYING) {
            throw new IllegalStateException("当前状态不允许替换任务：" + task.id());
        }
        task = replacement;
        lastResult = null;
        lastEvaluation = null;
        verifiedOutput = JsonNodeFactory.instance.objectNode();
        replanGeneration++;
        status = TaskStatus.PENDING;
    }

    void markAborted(String reason) {
        if (status == TaskStatus.VERIFIED) return;
        lastResult = AgentTaskResult.failure(task, reason);
        status = TaskStatus.ABORTED;
    }

    void markCancelled(String reason) {
        if (status == TaskStatus.VERIFIED) return;
        lastResult = AgentTaskResult.failure(task, reason);
        status = TaskStatus.CANCELLED;
    }

    void requireReplan() {
        requireStatus(TaskStatus.RETRY_PENDING);
        status = TaskStatus.REPLAN_REQUIRED;
    }

    private void requireStatus(TaskStatus... allowed) {
        for (TaskStatus candidate : allowed) {
            if (status == candidate) return;
        }
        throw new IllegalStateException(
            "任务 " + task.id() + " 当前状态 " + status + " 不允许该转换");
    }
}
