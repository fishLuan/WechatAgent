package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.clawbot.wechatbot.service.agent.state.AgentTaskState;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-scoped recorder; checkpoint failures degrade without breaking the Agent request. */
public final class AgentCheckpointRecorder {
    private final AgentCheckpointStore store;
    private final ObjectMapper mapper;
    private final String executionId;
    private AgentExecutionCheckpoint execution;
    private final Map<String, AgentTaskCheckpoint> tasks = new LinkedHashMap<>();
    private int planVersion = 1;
    private boolean available;

    private AgentCheckpointRecorder(
        AgentCheckpointStore store, ObjectMapper mapper, String executionId
    ) {
        this.store = store;
        this.mapper = mapper;
        this.executionId = executionId;
    }

    public static AgentCheckpointRecorder begin(
        AgentCheckpointStore store, ObjectMapper mapper, String executionId,
        AgentRequestContext requestContext, String request, List<AgentTask> plan
    ) {
        AgentCheckpointRecorder recorder = new AgentCheckpointRecorder(
            store, mapper, executionId);
        if (store == null || executionId == null || executionId.isBlank()) return recorder;
        try {
            AgentRequestContext context = requestContext == null
                ? AgentRequestContext.anonymous() : requestContext;
            recorder.execution = store.createExecution(
                executionId, context.userId(), context.messageId(), request);
            AgentExecutionSnapshot snapshot = store.savePlan(executionId, 1, plan);
            recorder.execution = snapshot.execution();
            snapshot.tasks().forEach(task -> recorder.tasks.put(task.getTaskId(), task));
            recorder.execution.setStatus(AgentCheckpointExecutionStatus.RUNNING);
            recorder.execution = store.saveExecution(recorder.execution);
            recorder.available = true;
        } catch (Exception error) {
            recorder.disable("初始化", error);
        }
        return recorder;
    }

    public static AgentCheckpointRecorder resume(
        AgentCheckpointStore store, ObjectMapper mapper,
        AgentExecutionSnapshot snapshot
    ) {
        AgentCheckpointRecorder recorder = new AgentCheckpointRecorder(
            store, mapper, snapshot.execution().getId());
        recorder.execution = snapshot.execution();
        recorder.planVersion = Math.max(1, recorder.execution.getPlanVersion());
        snapshot.tasks().forEach(task -> recorder.tasks.put(task.getTaskId(), task));
        recorder.available = store != null;
        return recorder;
    }

    public synchronized void outerRound(AgentExecutionState state) {
        if (!available) return;
        safely("保存外层轮次", () -> {
            copyProgress(state);
            execution.setStatus(AgentCheckpointExecutionStatus.RUNNING);
            execution = store.saveExecution(execution);
        });
    }

    public synchronized void taskStarted(
        AgentTaskState state, JsonNode resolvedInput
    ) {
        if (!available || state == null) return;
        safely("保存任务开始", () -> {
            AgentTaskCheckpoint checkpoint = task(state.task().id());
            checkpoint.setResolvedInputJson(write(resolvedInput));
            checkpoint.setStatus(TaskStatus.RUNNING);
            checkpoint.setAttemptCount(state.attemptCount());
            checkpoint.setReplanGeneration(state.replanGeneration());
            checkpoint.setSideEffect(isSideEffect(state.task()));
            if (checkpoint.getStartedAt() == null) checkpoint.setStartedAt(Instant.now());
            tasks.put(state.task().id(), store.saveTask(checkpoint));
        });
    }

    public synchronized void taskEvaluated(AgentTaskState state) {
        if (!available || state == null) return;
        safely("保存任务验收", () -> {
            AgentTaskCheckpoint checkpoint = task(state.task().id());
            checkpoint.setStatus(state.status());
            checkpoint.setAttemptCount(state.attemptCount());
            checkpoint.setReplanGeneration(state.replanGeneration());
            checkpoint.setResultJson(resultJson(state.lastResult()));
            checkpoint.setEvaluationJson(write(state.lastEvaluation()));
            checkpoint.setVerifiedOutputJson(write(state.verifiedOutput()));
            if (state.lastEvaluation() != null) {
                checkpoint.setErrorCode(state.lastEvaluation().passed()
                    ? "" : state.lastEvaluation().code());
                checkpoint.setErrorMessage(state.lastEvaluation().passed()
                    ? "" : state.lastEvaluation().reason());
            } else if (state.lastResult() != null && !state.lastResult().succeeded()) {
                checkpoint.setErrorMessage(state.lastResult().error());
            }
            if (state.status().terminal()) checkpoint.setCompletedAt(Instant.now());
            tasks.put(state.task().id(), store.saveTask(checkpoint));
        });
    }

    public synchronized void retryOrReplanState(
        AgentExecutionState state, AgentCheckpointExecutionStatus executionStatus
    ) {
        if (!available) return;
        safely("保存重试或重规划状态", () -> {
            for (AgentTaskState taskState : state.taskStates()) {
                AgentTaskCheckpoint checkpoint = task(taskState.task().id());
                checkpoint.setStatus(taskState.status());
                checkpoint.setAttemptCount(taskState.attemptCount());
                checkpoint.setReplanGeneration(taskState.replanGeneration());
                tasks.put(taskState.task().id(), store.saveTask(checkpoint));
            }
            copyProgress(state);
            execution.setStatus(executionStatus);
            execution = store.saveExecution(execution);
        });
    }

    public synchronized void planRevised(AgentExecutionState state) {
        if (!available) return;
        safely("保存重规划计划", () -> {
            planVersion++;
            AgentExecutionSnapshot snapshot = store.savePlan(
                executionId, planVersion, state.tasks());
            execution = snapshot.execution();
            tasks.clear();
            snapshot.tasks().forEach(task -> tasks.put(task.getTaskId(), task));
            copyProgress(state);
            execution.setStatus(AgentCheckpointExecutionStatus.RUNNING);
            execution = store.saveExecution(execution);
        });
    }

    public synchronized void finish(
        AgentExecutionState state, AgentCheckpointExecutionStatus status,
        String failureCode, String failureMessage
    ) {
        if (!available) return;
        safely("保存最终状态", () -> {
            for (AgentTaskState taskState : state.taskStates()) {
                taskEvaluated(taskState);
            }
            copyProgress(state);
            execution.setStatus(status);
            execution.setFailureCode(failureCode == null ? "" : failureCode);
            execution.setFailureMessage(failureMessage == null ? "" : failureMessage);
            execution = store.saveExecution(execution);
        });
    }

    public String executionId() { return executionId; }
    public boolean available() { return available; }

    private void copyProgress(AgentExecutionState state) {
        execution.setCurrentRound(state.outerRound());
        execution.setReplanCount(state.replanCount());
        execution.setTotalTaskExecutions(state.totalTaskExecutions());
        execution.setCompletedTaskIds(state.completedTaskIds());
        execution.setCancelledTaskIds(state.cancelledTaskIds());
        execution.setSideEffectsCompleted(state.hasCompletedSideEffects());
    }

    private AgentTaskCheckpoint task(String taskId) {
        AgentTaskCheckpoint checkpoint = tasks.get(taskId);
        if (checkpoint == null) {
            throw new IllegalStateException("找不到任务检查点：" + taskId);
        }
        return checkpoint;
    }

    private String resultJson(AgentTaskResult result) throws Exception {
        if (result == null) return "";
        ObjectNode root = mapper.createObjectNode();
        root.put("success", result.succeeded());
        root.put("text", result.text());
        root.set("texts", mapper.valueToTree(result.texts()));
        if (result.error() != null) root.put("error", result.error());
        ArrayNode attachments = root.putArray("attachments");
        for (AgentAttachment attachment : result.attachments()) {
            ObjectNode item = attachments.addObject();
            item.put("type", attachment.type().name());
            item.put("fileName", attachment.fileName());
            item.put("caption", attachment.caption());
            item.put("size", attachment.content().length);
        }
        return mapper.writeValueAsString(root);
    }

    private String write(Object value) throws Exception {
        return value == null ? "" : mapper.writeValueAsString(value);
    }

    private boolean isSideEffect(AgentTask task) {
        String text = task.instruction().toLowerCase();
        return text.contains("发送") || text.contains("订阅")
            || text.contains("取消") || text.contains("删除")
            || text.contains("创建定时") || text.contains("写入")
            || text.contains("send") || text.contains("subscribe")
            || text.contains("delete") || text.contains("write");
    }

    private void safely(String action, CheckedAction actionBody) {
        if (!available) return;
        try {
            actionBody.run();
        } catch (Exception error) {
            disable(action, error);
        }
    }

    private void disable(String action, Exception error) {
        available = false;
        System.err.println("[AGENT-CHECKPOINT] " + action + "失败，当前请求降级为不写检查点："
            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
