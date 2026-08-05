package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.acceptance.TaskDecision;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanMutationApplierTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanMutationValidator validator = new PlanMutationValidator(
        emptySkills(), 5, 3, 10);
    private final PlanMutationApplier applier = new PlanMutationApplier(validator);

    @Test
    void schedulesRetryWithoutExecutingTask() {
        AgentTask task = task("search", 0, List.of());
        AgentExecutionState state = stateWithDecision(task, TaskDecision.RETRY);

        applier.apply(state, new ReplanResult(
            List.of(new PlanMutation(
                PlanMutationType.RETRY_TASK, "search", null, "临时失败")),
            "重试"));

        assertEquals(TaskStatus.PENDING, state.taskState("search").status());
        assertEquals(1, state.taskState("search").attemptCount());
        assertEquals(1, state.replanCount());
    }

    @Test
    void insertsRepairTaskAndRewiresFailedTarget() {
        AgentTask search = task("search", 0, List.of());
        AgentTask select = task("select", 1, List.of("search"));
        AgentExecutionState state = new AgentExecutionState(
            "搜索并选择", List.of(search, select));
        state.markRunning(search);
        state.recordResult(
            AgentTaskResult.success(search, "{}", List.of()),
            TaskEvaluation.pass(mapper.createObjectNode()));
        state.markRunning(select);
        state.recordResult(
            AgentTaskResult.success(select, "{}", List.of()),
            evaluation(TaskDecision.REPLAN));
        AgentTask detail = task("detail", 1, List.of());

        applier.apply(state, new ReplanResult(
            List.of(new PlanMutation(
                PlanMutationType.INSERT_BEFORE, "select", detail, "缺少评分")),
            "补充详情"));

        assertEquals(List.of("search"), state.taskState("detail").task().dependencies());
        assertEquals(List.of("detail"), state.taskState("select").task().dependencies());
        assertEquals(TaskStatus.PENDING, state.taskState("detail").status());
        assertEquals(TaskStatus.PENDING, state.taskState("select").status());
        assertEquals(List.of("detail"), state.readyTasks(5).stream()
            .map(AgentTask::id).toList());
    }

    @Test
    void rejectsDependencyCycleBeforeApplyingAnything() {
        AgentTask first = task("first", 0, List.of());
        AgentTask second = task("second", 1, List.of("first"));
        AgentExecutionState state = new AgentExecutionState(
            "请求", List.of(first, second));
        state.markRunning(first);
        state.recordResult(
            AgentTaskResult.success(first, "{}", List.of()),
            evaluation(TaskDecision.REPLAN));
        AgentTask replacement = task("first", 0, List.of("second"));
        ReplanResult result = new ReplanResult(
            List.of(new PlanMutation(
                PlanMutationType.REPLACE_TASK, "first", replacement, "调整")),
            "会形成环");

        PlanMutationValidationResult validation = validator.validate(state, result);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("依赖环")));
        assertThrows(IllegalArgumentException.class, () -> applier.apply(state, result));
        assertEquals(List.of(), state.taskState("first").task().dependencies());
    }

    @Test
    void forbidsChangingVerifiedTask() {
        AgentTask task = task("done", 0, List.of());
        AgentExecutionState state = new AgentExecutionState("请求", List.of(task));
        state.markRunning(task);
        state.recordResult(
            AgentTaskResult.success(task, "完成", List.of()),
            TaskEvaluation.pass(mapper.createObjectNode()));

        PlanMutationValidationResult validation = validator.validate(
            state,
            new ReplanResult(List.of(new PlanMutation(
                PlanMutationType.REPLACE_TASK, "done", task, "不应允许")), ""));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
            .anyMatch(error -> error.contains("禁止修改")));
    }

    @Test
    void abortsTargetAndAllDownstreamTasks() {
        AgentTask first = task("first", 0, List.of());
        AgentTask second = task("second", 1, List.of("first"));
        AgentTask third = task("third", 2, List.of("second"));
        AgentExecutionState state = stateWithDecision(
            List.of(first, second, third), first, TaskDecision.REPLAN);

        applier.apply(state, new ReplanResult(
            List.of(new PlanMutation(
                PlanMutationType.ABORT_BRANCH, "first", null, "无法安全修复")), ""));

        assertEquals(TaskStatus.ABORTED, state.taskState("first").status());
        assertEquals(TaskStatus.ABORTED, state.taskState("second").status());
        assertEquals(TaskStatus.ABORTED, state.taskState("third").status());
    }

    @Test
    void rejectsReferenceWhoseSourceIsNotDeclaredAsDependency() {
        AgentTask source = task("source", 0, List.of());
        AgentTask failed = task("target", 1, List.of());
        AgentExecutionState state = stateWithDecision(
            List.of(source, failed), failed, TaskDecision.REPLAN);
        var input = mapper.createObjectNode();
        input.putObject("id").put("$ref", "source.output.id");
        AgentTask replacement = new AgentTask(
            "target", 1, AgentTaskType.CHAT_TOOL, "", "替换任务",
            input, mapper.createObjectNode(), List.of(), List.of());

        PlanMutationValidationResult validation = validator.validate(
            state, new ReplanResult(List.of(new PlanMutation(
                PlanMutationType.REPLACE_TASK, "target", replacement, "引用来源")), ""));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
            .anyMatch(error -> error.contains("未声明 $ref 来源依赖")));
    }

    private AgentExecutionState stateWithDecision(
        AgentTask task, TaskDecision decision
    ) {
        return stateWithDecision(List.of(task), task, decision);
    }

    private AgentExecutionState stateWithDecision(
        List<AgentTask> tasks, AgentTask target, TaskDecision decision
    ) {
        AgentExecutionState state = new AgentExecutionState("请求", tasks);
        state.markRunning(target);
        state.recordResult(
            AgentTaskResult.success(target, "{}", List.of()), evaluation(decision));
        return state;
    }

    private TaskEvaluation evaluation(TaskDecision decision) {
        return new TaskEvaluation(
            decision, "TEST", "测试", mapper.createObjectNode(), List.of(), "修复");
    }

    private AgentTask task(String id, int order, List<String> dependencies) {
        return new AgentTask(
            id, order, AgentTaskType.CHAT_TOOL, "任务 " + id, dependencies);
    }

    private SkillCatalog emptySkills() {
        return new SkillCatalog() {
            @Override public List<SkillDefinition> definitions() { return List.of(); }
            @Override public boolean contains(String name) { return false; }
        };
    }
}
