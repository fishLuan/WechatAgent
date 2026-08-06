package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoAgentCheckpointStoreTests {
    private static final Instant NOW = Instant.parse("2026-08-05T06:00:00Z");

    @Test
    void createsExecutionWithStableInitialCheckpoint() {
        AgentExecutionCheckpointRepository executions =
            mock(AgentExecutionCheckpointRepository.class);
        when(executions.existsById("TASK-1")).thenReturn(false);
        when(executions.save(any())).thenAnswer(call -> call.getArgument(0));
        MongoAgentCheckpointStore store = store(
            executions, mock(AgentTaskCheckpointRepository.class));

        AgentExecutionCheckpoint saved = store.createExecution(
            "TASK-1", "user-1", 10L, "搜索三体");

        assertEquals(AgentCheckpointExecutionStatus.CREATED, saved.getStatus());
        assertEquals(NOW, saved.getLastCheckpointAt());
        assertEquals(AgentExecutionCheckpoint.CURRENT_SCHEMA_VERSION,
            saved.getSchemaVersion());
    }

    @Test
    void savesVersionedPlanAndCanDeserializeTask() {
        AgentExecutionCheckpointRepository executions =
            mock(AgentExecutionCheckpointRepository.class);
        AgentTaskCheckpointRepository tasks =
            mock(AgentTaskCheckpointRepository.class);
        AgentExecutionCheckpoint execution = AgentExecutionCheckpoint.create(
            "TASK-1", "user-1", 10L, "搜索三体", NOW);
        when(executions.findById("TASK-1")).thenReturn(Optional.of(execution));
        when(tasks.findById("TASK-1:task-1")).thenReturn(Optional.empty());
        MongoAgentCheckpointStore store = store(executions, tasks);
        AgentTask task = new AgentTask(
            "task-1", 0, AgentTaskType.SKILL, "bilibili",
            "搜索三体", List.of());

        store.savePlan("TASK-1", 1, List.of(task));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<AgentTaskCheckpoint>> captor =
            ArgumentCaptor.forClass(Iterable.class);
        verify(tasks).saveAll(captor.capture());
        AgentTaskCheckpoint checkpoint = captor.getValue().iterator().next();
        assertEquals("TASK-1:task-1", checkpoint.getId());
        assertEquals("bilibili", checkpoint.getSkillName());
        assertEquals(task, store.deserializeTask(checkpoint));
        assertEquals(1, execution.getPlanVersion());
        assertEquals(List.of("task-1"), execution.getTaskIds());
    }

    @Test
    void rejectsDuplicateTaskIdsBeforeWritingPlan() {
        AgentExecutionCheckpointRepository executions =
            mock(AgentExecutionCheckpointRepository.class);
        MongoAgentCheckpointStore store = store(
            executions, mock(AgentTaskCheckpointRepository.class));
        AgentTask first = AgentTask.chat("任务一");
        AgentTask duplicate = AgentTask.chat("任务二");

        assertThrows(IllegalArgumentException.class,
            () -> store.savePlan("TASK-1", 1, List.of(first, duplicate)));
    }

    @Test
    void loadsExecutionAndOrderedTaskCheckpointsAsSnapshot() {
        AgentExecutionCheckpointRepository executions =
            mock(AgentExecutionCheckpointRepository.class);
        AgentTaskCheckpointRepository tasks =
            mock(AgentTaskCheckpointRepository.class);
        AgentExecutionCheckpoint execution = AgentExecutionCheckpoint.create(
            "TASK-1", "user-1", 10L, "请求", NOW);
        AgentTaskCheckpoint task = AgentTaskCheckpoint.fromTask(
            "TASK-1", 1, AgentTask.chat("请求"),
            "{}", NOW);
        when(executions.findById("TASK-1")).thenReturn(Optional.of(execution));
        when(tasks.findByExecutionIdOrderByOrderAsc("TASK-1"))
            .thenReturn(List.of(task));

        AgentExecutionSnapshot snapshot = store(executions, tasks)
            .load("TASK-1").orElseThrow();

        assertEquals("TASK-1", snapshot.execution().getId());
        assertEquals(1, snapshot.tasks().size());
    }

    private MongoAgentCheckpointStore store(
        AgentExecutionCheckpointRepository executions,
        AgentTaskCheckpointRepository tasks
    ) {
        return new MongoAgentCheckpointStore(
            executions, tasks, new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
