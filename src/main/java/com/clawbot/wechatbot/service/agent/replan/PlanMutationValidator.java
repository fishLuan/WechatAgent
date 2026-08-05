package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.service.agent.reference.ReferencePolicy;
import com.clawbot.wechatbot.service.agent.reference.ResultReference;
import com.clawbot.wechatbot.service.agent.reference.ReferenceResolutionException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在原子应用前验证修改权限、任务预算、依赖完整性和依赖环。 */
public final class PlanMutationValidator {
    private final SkillCatalog skills;
    private final int maxMutations;
    private final int maxGeneratedTasks;
    private final int maxTotalTasks;
    private final ReferencePolicy referencePolicy;

    public PlanMutationValidator(
        SkillCatalog skills,
        int maxMutations,
        int maxGeneratedTasks,
        int maxTotalTasks
    ) {
        this(skills, maxMutations, maxGeneratedTasks, maxTotalTasks,
            ReferencePolicy.defaults());
    }

    public PlanMutationValidator(
        SkillCatalog skills,
        int maxMutations,
        int maxGeneratedTasks,
        int maxTotalTasks,
        ReferencePolicy referencePolicy
    ) {
        this.skills = skills;
        this.maxMutations = Math.max(1, maxMutations);
        this.maxGeneratedTasks = Math.max(0, maxGeneratedTasks);
        this.maxTotalTasks = Math.max(1, maxTotalTasks);
        this.referencePolicy = referencePolicy;
    }

    public PlanMutationValidationResult validate(
        AgentExecutionState state, ReplanResult result
    ) {
        List<String> errors = new ArrayList<>();
        if (result == null || result.mutations().isEmpty()) {
            return new PlanMutationValidationResult(
                false, List.of("重规划没有返回任何修改"));
        }
        if (result.mutations().size() > maxMutations) {
            errors.add("单次计划修改数超过上限 " + maxMutations);
        }

        Map<String, AgentTask> graph = new LinkedHashMap<>();
        state.tasks().forEach(task -> graph.put(task.id(), task));
        int generatedTasks = 0;
        Set<String> touchedTargets = new HashSet<>();

        for (PlanMutation mutation : result.mutations()) {
            String targetId = mutation.targetTaskId();
            AgentTask target = graph.get(targetId);
            if (target == null) {
                errors.add("目标任务不存在：" + targetId);
                continue;
            }
            TaskStatus status = state.containsTask(targetId)
                ? state.taskState(targetId).status() : TaskStatus.PENDING;
            if (status == TaskStatus.VERIFIED
                || status == TaskStatus.RUNNING
                || status == TaskStatus.VERIFYING) {
                errors.add("禁止修改当前状态为 " + status + " 的任务：" + targetId);
                continue;
            }
            if (!touchedTargets.add(targetId)
                && mutation.type() != PlanMutationType.ABORT_BRANCH) {
                errors.add("同一次重规划重复修改目标任务：" + targetId);
            }

            switch (mutation.type()) {
                case RETRY_TASK -> {
                    if (status != TaskStatus.RETRY_PENDING
                        && status != TaskStatus.REPLAN_REQUIRED) {
                        errors.add("只有待重试或待重规划任务可以重试：" + targetId);
                    }
                }
                case REPLACE_TASK -> {
                    AgentTask replacement = mutation.task();
                    if (!targetId.equals(replacement.id())) {
                        errors.add("替换任务必须保持原 ID：" + targetId);
                    }
                    validateTask(replacement, errors);
                    graph.put(targetId, replacement);
                }
                case INSERT_BEFORE -> {
                    AgentTask inserted = mutation.task();
                    generatedTasks++;
                    if (graph.containsKey(inserted.id())) {
                        errors.add("插入任务 ID 已存在：" + inserted.id());
                        continue;
                    }
                    validateTask(inserted, errors);
                    List<String> dependencies = inserted.dependencies().isEmpty()
                        ? target.dependencies() : inserted.dependencies();
                    AgentTask normalized = copyTask(inserted, dependencies);
                    graph.put(normalized.id(), normalized);
                    graph.put(targetId, copyTask(target, List.of(normalized.id())));
                }
                case ABORT_BRANCH -> {
                    // 不修改图结构，由应用器终止目标及其所有下游任务。
                }
            }
        }

        if (generatedTasks > maxGeneratedTasks) {
            errors.add("新增任务数超过上限 " + maxGeneratedTasks);
        }
        if (graph.size() > maxTotalTasks) {
            errors.add("重规划后任务总数超过上限 " + maxTotalTasks);
        }
        validateDependencies(graph, errors);
        graph.values().forEach(task -> validateReferences(task, graph, errors));
        if (hasCycle(graph)) errors.add("重规划后的任务图存在依赖环");
        return errors.isEmpty()
            ? PlanMutationValidationResult.success()
            : new PlanMutationValidationResult(false, errors);
    }

    private void validateReferences(
        AgentTask task, Map<String, AgentTask> graph, List<String> errors
    ) {
        int[] count = {0};
        validateReferenceNode(task.input(), task, graph, errors, count, 0);
    }

    private void validateReferenceNode(
        JsonNode node,
        AgentTask task,
        Map<String, AgentTask> graph,
        List<String> errors,
        int[] count,
        int depth
    ) {
        if (node == null) return;
        if (depth > referencePolicy.maxDepth()) {
            errors.add("任务 " + task.id() + " 的 $ref 输入嵌套过深");
            return;
        }
        if (node.isObject() && node.has("$ref")) {
            if (node.size() != 1 || !node.path("$ref").isTextual()) {
                errors.add("任务 " + task.id() + " 包含歧义 $ref 节点");
                return;
            }
            if (++count[0] > referencePolicy.maxReferencesPerTask()) {
                errors.add("任务 " + task.id() + " 的 $ref 数量超过限制");
                return;
            }
            try {
                ResultReference reference = ResultReference.parse(
                    node.path("$ref").asText(), referencePolicy.maxPathLength());
                if (!graph.containsKey(reference.taskId())) {
                    errors.add("任务 " + task.id() + " 引用了不存在的任务："
                        + reference.taskId());
                } else if (task.id().equals(reference.taskId())) {
                    errors.add("任务不能引用自身输出：" + task.id());
                } else if (!task.dependencies().contains(reference.taskId())) {
                    errors.add("任务 " + task.id() + " 未声明 $ref 来源依赖："
                        + reference.taskId());
                }
            } catch (ReferenceResolutionException error) {
                errors.add("任务 " + task.id() + " 的 " + error.getMessage());
            }
            return;
        }
        if (node.isContainerNode()) {
            node.forEach(child -> validateReferenceNode(
                child, task, graph, errors, count, depth + 1));
        }
    }

    private void validateTask(AgentTask task, List<String> errors) {
        if (task.type() == AgentTaskType.SKILL
            && (skills == null || !skills.contains(task.skillName()))) {
            errors.add("新任务引用了未知 Skill：" + task.skillName());
        }
    }

    private void validateDependencies(
        Map<String, AgentTask> graph, List<String> errors
    ) {
        graph.values().forEach(task -> task.dependencies().forEach(dependency -> {
            if (!graph.containsKey(dependency)) {
                errors.add("任务 " + task.id() + " 依赖不存在：" + dependency);
            }
            if (task.id().equals(dependency)) {
                errors.add("任务不能依赖自身：" + task.id());
            }
        }));
    }

    private boolean hasCycle(Map<String, AgentTask> graph) {
        Map<String, Integer> colors = new HashMap<>();
        for (String id : graph.keySet()) {
            if (visit(id, graph, colors)) return true;
        }
        return false;
    }

    private boolean visit(
        String id, Map<String, AgentTask> graph, Map<String, Integer> colors
    ) {
        int color = colors.getOrDefault(id, 0);
        if (color == 1) return true;
        if (color == 2) return false;
        colors.put(id, 1);
        AgentTask task = graph.get(id);
        if (task != null) {
            for (String dependency : task.dependencies()) {
                if (graph.containsKey(dependency) && visit(dependency, graph, colors)) {
                    return true;
                }
            }
        }
        colors.put(id, 2);
        return false;
    }

    private AgentTask copyTask(AgentTask source, List<String> dependencies) {
        return new AgentTask(
            source.id(), source.order(), source.type(), source.skillName(),
            source.instruction(), source.input(), source.expectedOutput(),
            source.acceptanceCriteria(), dependencies);
    }
}
