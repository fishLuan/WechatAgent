package com.clawbot.wechatbot.service.agent.state;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.acceptance.TaskDecision;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionStateTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onlyVerifiedTaskUnlocksItsDependentTask() {
        AgentTask first = task("first", 0, List.of());
        AgentTask second = task("second", 1, List.of("first"));
        AgentExecutionState state = new AgentExecutionState(
            "先查询再生成", List.of(first, second));

        assertEquals(List.of(first), state.readyTasks(5));
        state.markRunning(first);
        AgentTaskResult result = AgentTaskResult.success(
            first, "{\"value\":1}", List.of());
        state.recordResult(result, TaskEvaluation.pass(mapper.createObjectNode()));

        assertEquals(TaskStatus.VERIFIED, state.taskState("first").status());
        assertEquals(List.of(second), state.readyTasks(5));
        assertEquals(1, state.totalTaskExecutions());
    }

    @Test
    void retryDecisionIsRecordedButNotAutomaticallyExecutedYet() {
        AgentTask task = task("first", 0, List.of());
        AgentExecutionState state = new AgentExecutionState("查询", List.of(task));
        state.markRunning(task);
        AgentTaskResult result = AgentTaskResult.failure(task, "服务暂时不可用");
        state.recordResult(result, evaluation(TaskDecision.RETRY));

        assertEquals(TaskStatus.RETRY_PENDING, state.taskState("first").status());
        assertTrue(state.hasRetryPendingTasks());
        assertTrue(state.readyTasks(5).isEmpty());
        assertEquals(1, state.taskState("first").attemptCount());
    }

    @Test
    void replanDecisionDoesNotUnlockDependentTask() {
        AgentTask first = task("first", 0, List.of());
        AgentTask second = task("second", 1, List.of("first"));
        AgentExecutionState state = new AgentExecutionState(
            "搜索后订阅", List.of(first, second));
        state.markRunning(first);
        state.recordResult(
            AgentTaskResult.success(first, "{}", List.of()),
            evaluation(TaskDecision.REPLAN));

        assertEquals(
            TaskStatus.REPLAN_REQUIRED, state.taskState("first").status());
        assertTrue(state.hasReplanRequiredTasks());
        assertTrue(state.readyTasks(5).isEmpty());
    }

    @Test
    void rejectsDuplicateTaskIds() {
        AgentTask first = task("same", 0, List.of());
        AgentTask second = task("same", 1, List.of());

        assertThrows(
            IllegalArgumentException.class,
            () -> new AgentExecutionState("请求", List.of(first, second)));
    }

    private TaskEvaluation evaluation(TaskDecision decision) {
        return new TaskEvaluation(
            decision, "TEST", "测试", mapper.createObjectNode(),
            List.of(), "修正");
    }

    private AgentTask task(String id, int order, List<String> dependencies) {
        return new AgentTask(
            id, order, AgentTaskType.CHAT_TOOL, "任务 " + id, dependencies);
    }
}
