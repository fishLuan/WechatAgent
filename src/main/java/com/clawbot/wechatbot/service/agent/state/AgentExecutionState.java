package com.clawbot.wechatbot.service.agent.state;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import com.clawbot.wechatbot.service.agent.reference.DataLineageRecord;
import com.clawbot.wechatbot.service.agent.reference.ReferenceResolutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/** 一次外层 Agent 请求的任务图和可信执行状态。 */
public final class AgentExecutionState {
    private final String originalUserRequest;
    private final LinkedHashMap<String, AgentTaskState> tasks = new LinkedHashMap<>();
    private int outerRound;
    private int replanCount;
    private int totalTaskExecutions;
    private boolean aborted;
    private final List<DataLineageRecord> lineage = new java.util.ArrayList<>();

    public AgentExecutionState(String originalUserRequest, List<AgentTask> plannedTasks) {
        this.originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        if (plannedTasks == null) return;
        plannedTasks.stream()
            .sorted(Comparator.comparingInt(AgentTask::order))
            .forEach(task -> {
                if (tasks.putIfAbsent(task.id(), new AgentTaskState(task)) != null) {
                    throw new IllegalArgumentException("任务 ID 重复：" + task.id());
                }
            });
    }

    public String originalUserRequest() {
        return originalUserRequest;
    }

    public int outerRound() {
        return outerRound;
    }

    public int replanCount() {
        return replanCount;
    }

    public int totalTaskExecutions() {
        return totalTaskExecutions;
    }

    public boolean aborted() {
        return aborted;
    }

    public void nextOuterRound() {
        outerRound++;
    }

    public void restoreProgress(
        int restoredOuterRound, int restoredReplanCount,
        int restoredTotalTaskExecutions
    ) {
        outerRound = Math.max(0, restoredOuterRound);
        replanCount = Math.max(0, restoredReplanCount);
        totalTaskExecutions = Math.max(0, restoredTotalTaskExecutions);
    }

    public void restoreTask(
        String taskId, TaskStatus status, int attemptCount,
        int replanGeneration, AgentTaskResult result,
        TaskEvaluation evaluation, JsonNode verifiedOutput
    ) {
        state(taskId).restore(status, attemptCount, replanGeneration,
            result, evaluation, verifiedOutput);
    }

    public List<AgentTask> readyTasks(int limit) {
        return tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.PENDING)
            .filter(state -> dependenciesVerified(state.task()))
            .sorted(Comparator.comparingInt(state -> state.task().order()))
            .limit(Math.max(1, limit))
            .map(AgentTaskState::task)
            .toList();
    }

    public void markRunning(AgentTask task) {
        state(task.id()).markRunning();
        totalTaskExecutions++;
    }

    public void recordResult(AgentTaskResult result, TaskEvaluation evaluation) {
        AgentTaskState state = state(result.task().id());
        state.markVerifying(result);
        state.applyEvaluation(evaluation);
    }

    public Map<String, AgentTaskResult> verifiedResults() {
        Map<String, AgentTaskResult> results = new LinkedHashMap<>();
        tasks.forEach((id, state) -> {
            if (state.status() == TaskStatus.VERIFIED && state.lastResult() != null) {
                results.put(id, state.lastResult());
            }
        });
        return Map.copyOf(results);
    }

    public JsonNode verifiedOutput(String taskId) {
        AgentTaskState task = state(taskId);
        if (task.status() != TaskStatus.VERIFIED) {
            throw new ReferenceResolutionException(
                "REF_SOURCE_NOT_VERIFIED", "引用来源任务尚未通过验收：" + taskId);
        }
        return task.verifiedOutput();
    }

    public JsonNode readVerifiedValue(String taskId, String path) {
        JsonNode current = verifiedOutput(taskId);
        String remaining = path == null ? "" : path;
        if (!remaining.startsWith("$")) {
            throw new ReferenceResolutionException(
                "REF_INVALID_PATH", "引用路径必须以 $ 开头");
        }
        int index = 1;
        while (index < remaining.length()) {
            char marker = remaining.charAt(index);
            if (marker == '.') {
                int next = index + 1;
                while (next < remaining.length()
                    && remaining.charAt(next) != '.'
                    && remaining.charAt(next) != '[') next++;
                String field = remaining.substring(index + 1, next);
                if (!current.isObject() || !current.has(field)) {
                    throw new ReferenceResolutionException(
                        "REF_PATH_NOT_FOUND", "引用字段不存在：" + path);
                }
                current = current.get(field);
                index = next;
            } else if (marker == '[') {
                int end = remaining.indexOf(']', index);
                if (end < 0 || !current.isArray()) {
                    throw new ReferenceResolutionException(
                        "REF_PATH_NOT_FOUND", "引用数组路径不存在：" + path);
                }
                try {
                    int arrayIndex = Integer.parseInt(
                        remaining.substring(index + 1, end));
                    if (arrayIndex < 0 || arrayIndex >= current.size()) {
                        throw new ReferenceResolutionException(
                            "REF_PATH_NOT_FOUND", "引用数组下标越界：" + path);
                    }
                    current = current.get(arrayIndex);
                } catch (NumberFormatException error) {
                    throw new ReferenceResolutionException(
                        "REF_INVALID_PATH", "引用数组下标无效：" + path);
                }
                index = end + 1;
            } else {
                throw new ReferenceResolutionException(
                    "REF_INVALID_PATH", "引用路径格式无效：" + path);
            }
        }
        if (current == null || current.isMissingNode()) {
            throw new ReferenceResolutionException(
                "REF_PATH_NOT_FOUND", "引用路径不存在：" + path);
        }
        return current.deepCopy();
    }

    public void recordLineage(List<DataLineageRecord> records) {
        if (records != null) lineage.addAll(records);
    }

    public List<DataLineageRecord> lineage() {
        return List.copyOf(lineage);
    }

    public List<DataLineageRecord> lineageForTarget(String taskId) {
        return lineage.stream()
            .filter(record -> record.targetTaskId().equals(taskId)).toList();
    }

    public Map<String, AgentTaskResult> verifiedDependencies(AgentTask task) {
        Map<String, AgentTaskResult> results = new LinkedHashMap<>();
        for (String dependencyId : task.dependencies()) {
            AgentTaskState dependency = tasks.get(dependencyId);
            if (dependency != null && dependency.status() == TaskStatus.VERIFIED
                && dependency.lastResult() != null) {
                results.put(dependencyId, dependency.lastResult());
            }
        }
        return Map.copyOf(results);
    }

    public List<AgentTaskState> taskStates() {
        return List.copyOf(tasks.values());
    }

    public AgentTaskState taskState(String taskId) {
        return state(taskId);
    }

    public boolean containsTask(String taskId) {
        return taskId != null && tasks.containsKey(taskId);
    }

    public List<AgentTask> tasks() {
        return tasks.values().stream().map(AgentTaskState::task)
            .sorted(Comparator.comparingInt(AgentTask::order)).toList();
    }

    public boolean hasUnfinishedTasks() {
        return tasks.values().stream().anyMatch(state -> !state.status().terminal());
    }

    public boolean hasPendingTasks() {
        return tasks.values().stream()
            .anyMatch(state -> state.status() == TaskStatus.PENDING);
    }

    public boolean hasRetryPendingTasks() {
        return tasks.values().stream()
            .anyMatch(state -> state.status() == TaskStatus.RETRY_PENDING);
    }

    public boolean hasReplanRequiredTasks() {
        return tasks.values().stream()
            .anyMatch(state -> state.status() == TaskStatus.REPLAN_REQUIRED);
    }

    public void failUnresolvedTasks(String reason) {
        tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.PENDING)
            .forEach(state -> state.markFailed(reason));
    }

    public void abort() {
        aborted = true;
        tasks.values().stream()
            .filter(state -> !state.status().terminal())
            .forEach(state -> state.markFailed("Agent 请求已终止"));
    }

    public void cancelUnfinished() {
        aborted = true;
        tasks.values().stream()
            .filter(state -> !state.status().terminal())
            .forEach(state -> state.markCancelled("用户已取消任务"));
    }

    public List<String> completedTaskIds() {
        return tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.VERIFIED)
            .map(state -> state.task().id()).toList();
    }

    public List<String> cancelledTaskIds() {
        return tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.CANCELLED)
            .map(state -> state.task().id()).toList();
    }

    public boolean hasCompletedSideEffects() {
        return tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.VERIFIED)
            .map(state -> state.task().instruction().toLowerCase())
            .anyMatch(text -> text.contains("发送") || text.contains("订阅")
                || text.contains("取消") || text.contains("删除")
                || text.contains("创建定时") || text.contains("写入")
                || text.contains("send") || text.contains("email")
                || text.contains("subscribe") || text.contains("delete")
                || text.contains("create schedule") || text.contains("write"));
    }

    public void incrementReplanCount() {
        replanCount++;
    }

    public void scheduleRetry(String taskId) {
        state(taskId).scheduleRetry();
    }

    public void requireReplan(String taskId) {
        state(taskId).requireReplan();
    }

    public void acceptBestEffort(String taskId, JsonNode output) {
        state(taskId).acceptBestEffort(output);
    }

    public void failTask(String taskId, String reason) {
        state(taskId).markFailed(reason);
    }

    public List<AgentTaskState> retryPendingTaskStates() {
        return tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.RETRY_PENDING)
            .sorted(Comparator.comparingInt(state -> state.task().order()))
            .toList();
    }

    public List<AgentTaskState> replanRequiredTaskStates() {
        return tasks.values().stream()
            .filter(state -> state.status() == TaskStatus.REPLAN_REQUIRED)
            .sorted(Comparator.comparingInt(state -> state.task().order()))
            .toList();
    }

    public void replaceTask(String taskId, AgentTask replacement) {
        if (!taskId.equals(replacement.id())) {
            throw new IllegalArgumentException("替换任务必须保持原任务 ID");
        }
        state(taskId).replaceTask(replacement);
    }

    public void insertTaskBefore(String targetTaskId, AgentTask inserted) {
        if (tasks.containsKey(inserted.id())) {
            throw new IllegalArgumentException("任务 ID 重复：" + inserted.id());
        }
        AgentTaskState targetState = state(targetTaskId);
        AgentTask target = targetState.task();
        if (targetState.status() == TaskStatus.VERIFIED
            || targetState.status() == TaskStatus.RUNNING
            || targetState.status() == TaskStatus.VERIFYING) {
            throw new IllegalStateException("当前状态不允许在任务前插入步骤：" + targetTaskId);
        }
        List<String> insertedDependencies = inserted.dependencies().isEmpty()
            ? target.dependencies() : inserted.dependencies();
        AgentTask normalizedInserted = copyTask(
            inserted, inserted.id(), inserted.order(), insertedDependencies);
        AgentTask rewiredTarget = copyTask(
            target, target.id(), target.order(), List.of(normalizedInserted.id()));
        targetState.replaceTask(rewiredTarget);
        tasks.put(normalizedInserted.id(), new AgentTaskState(normalizedInserted));
    }

    public void abortBranch(String rootTaskId, String reason) {
        Set<String> branch = new LinkedHashSet<>();
        branch.add(rootTaskId);
        boolean changed;
        do {
            changed = false;
            for (AgentTaskState candidate : tasks.values()) {
                if (!branch.contains(candidate.task().id())
                    && candidate.task().dependencies().stream().anyMatch(branch::contains)) {
                    changed |= branch.add(candidate.task().id());
                }
            }
        } while (changed);
        branch.forEach(id -> state(id).markAborted(reason));
    }

    private boolean dependenciesVerified(AgentTask task) {
        for (String dependencyId : task.dependencies()) {
            AgentTaskState dependency = tasks.get(dependencyId);
            if (dependency == null || dependency.status() != TaskStatus.VERIFIED) {
                return false;
            }
        }
        return true;
    }

    private AgentTaskState state(String id) {
        AgentTaskState state = tasks.get(id);
        if (state == null) throw new IllegalArgumentException("未知任务：" + id);
        return state;
    }

    private AgentTask copyTask(
        AgentTask source, String id, int order, List<String> dependencies
    ) {
        return new AgentTask(
            id, order, source.type(), source.skillName(), source.instruction(),
            source.input(), source.expectedOutput(), source.acceptanceCriteria(),
            dependencies);
    }
}
