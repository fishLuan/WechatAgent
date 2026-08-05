package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.clawbot.wechatbot.service.agent.state.AgentTaskState;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCheckpointRecorderTests {
    @Test
    void recordsPlanResolvedInputEvaluationAndFinalStatus() {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryStore store = new InMemoryStore(mapper);
        AgentTask task = AgentTask.chat("查询杭州天气");
        AgentExecutionState state = new AgentExecutionState(
            "查询杭州天气", List.of(task));
        AgentCheckpointRecorder recorder = AgentCheckpointRecorder.begin(
            store, mapper, "TASK-1", new AgentRequestContext("user-1", 10L),
            "查询杭州天气", List.of(task));

        state.nextOuterRound();
        recorder.outerRound(state);
        state.markRunning(task);
        recorder.taskStarted(
            state.taskState(task.id()), mapper.createObjectNode().put("city", "杭州"));
        AgentTaskResult result = AgentTaskResult.success(
            task, "杭州晴", List.of());
        state.recordResult(result, TaskEvaluation.pass(
            mapper.createObjectNode().put("text", "杭州晴")));
        recorder.taskEvaluated(state.taskState(task.id()));
        recorder.finish(state, AgentCheckpointExecutionStatus.SUCCEEDED, "", "");

        AgentExecutionSnapshot snapshot = store.load("TASK-1").orElseThrow();
        assertEquals(AgentCheckpointExecutionStatus.SUCCEEDED,
            snapshot.execution().getStatus());
        assertEquals(List.of("task-1"),
            snapshot.execution().getCompletedTaskIds());
        AgentTaskCheckpoint savedTask = snapshot.tasks().get(0);
        assertEquals(TaskStatus.VERIFIED, savedTask.getStatus());
        assertTrue(savedTask.getResolvedInputJson().contains("杭州"));
        assertTrue(savedTask.getResultJson().contains("杭州晴"));
        assertTrue(savedTask.getVerifiedOutputJson().contains("杭州晴"));
    }

    private static final class InMemoryStore implements AgentCheckpointStore {
        private final ObjectMapper mapper;
        private final Map<String, AgentExecutionCheckpoint> executions =
            new LinkedHashMap<>();
        private final Map<String, AgentTaskCheckpoint> tasks = new LinkedHashMap<>();

        private InMemoryStore(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public AgentExecutionCheckpoint createExecution(
            String executionId, String userId, Long sourceMessageId, String request
        ) {
            AgentExecutionCheckpoint checkpoint = AgentExecutionCheckpoint.create(
                executionId, userId, sourceMessageId, request, Instant.now());
            executions.put(executionId, checkpoint);
            return checkpoint;
        }

        @Override
        public AgentExecutionCheckpoint saveExecution(AgentExecutionCheckpoint execution) {
            executions.put(execution.getId(), execution);
            return execution;
        }

        @Override
        public AgentExecutionSnapshot savePlan(
            String executionId, int planVersion, List<AgentTask> plan
        ) {
            AgentExecutionCheckpoint execution = executions.get(executionId);
            List<AgentTaskCheckpoint> saved = new ArrayList<>();
            for (AgentTask task : plan) {
                String id = AgentTaskCheckpoint.checkpointId(executionId, task.id());
                AgentTaskCheckpoint checkpoint = tasks.get(id);
                try {
                    String json = mapper.writeValueAsString(task);
                    if (checkpoint == null) {
                        checkpoint = AgentTaskCheckpoint.fromTask(
                            executionId, planVersion, task, json, Instant.now());
                    } else {
                        checkpoint.updateDefinition(planVersion, task, json, Instant.now());
                    }
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                tasks.put(id, checkpoint);
                saved.add(checkpoint);
            }
            execution.updatePlan(planVersion,
                plan.stream().map(AgentTask::id).toList(), Instant.now());
            return new AgentExecutionSnapshot(execution, saved);
        }

        @Override
        public AgentTaskCheckpoint saveTask(AgentTaskCheckpoint checkpoint) {
            tasks.put(checkpoint.getId(), checkpoint);
            return checkpoint;
        }

        @Override
        public Optional<AgentExecutionSnapshot> load(String executionId) {
            AgentExecutionCheckpoint execution = executions.get(executionId);
            if (execution == null) return Optional.empty();
            return Optional.of(new AgentExecutionSnapshot(execution,
                tasks.values().stream()
                    .filter(task -> executionId.equals(task.getExecutionId()))
                    .sorted(java.util.Comparator.comparingInt(AgentTaskCheckpoint::getOrder))
                    .toList()));
        }

        @Override
        public List<AgentExecutionSnapshot> findRecoverableExecutions() {
            return List.of();
        }

        @Override
        public boolean tryAcquireRecoveryLease(
            String executionId, String owner, java.time.Duration duration
        ) {
            return true;
        }

        @Override
        public void releaseRecoveryLease(String executionId, String owner) {
        }

        @Override
        public List<AgentExecutionCheckpoint> findUndeliveredRecoveryResults() {
            return List.of();
        }

        @Override
        public List<AgentExecutionCheckpoint> findUnnotifiedRecoveryConfirmations() {
            return List.of();
        }

        @Override
        public AgentTask deserializeTask(AgentTaskCheckpoint checkpoint) {
            try {
                return mapper.readValue(checkpoint.getTaskJson(), AgentTask.class);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
