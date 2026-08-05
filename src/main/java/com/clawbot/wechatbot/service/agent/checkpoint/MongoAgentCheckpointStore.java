package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class MongoAgentCheckpointStore implements AgentCheckpointStore {
    private static final List<AgentCheckpointExecutionStatus> RECOVERABLE_STATUSES =
        Arrays.stream(AgentCheckpointExecutionStatus.values())
            .filter(AgentCheckpointExecutionStatus::recoverable)
            .toList();

    private final AgentExecutionCheckpointRepository executions;
    private final AgentTaskCheckpointRepository tasks;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MongoTemplate mongoTemplate;

    @Autowired
    public MongoAgentCheckpointStore(
        AgentExecutionCheckpointRepository executions,
        AgentTaskCheckpointRepository tasks,
        ObjectMapper mapper,
        MongoTemplate mongoTemplate
    ) {
        this(executions, tasks, mapper, Clock.systemUTC(), mongoTemplate);
    }

    MongoAgentCheckpointStore(
        AgentExecutionCheckpointRepository executions,
        AgentTaskCheckpointRepository tasks,
        ObjectMapper mapper,
        Clock clock
    ) {
        this(executions, tasks, mapper, clock, null);
    }

    MongoAgentCheckpointStore(
        AgentExecutionCheckpointRepository executions,
        AgentTaskCheckpointRepository tasks,
        ObjectMapper mapper,
        Clock clock,
        MongoTemplate mongoTemplate
    ) {
        this.executions = executions;
        this.tasks = tasks;
        this.mapper = mapper;
        this.clock = clock;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public AgentExecutionCheckpoint createExecution(
        String executionId, String userId, Long sourceMessageId,
        String originalRequest
    ) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId不能为空");
        }
        if (executions.existsById(executionId)) {
            throw new IllegalStateException("Agent执行实例已存在：" + executionId);
        }
        return executions.save(AgentExecutionCheckpoint.create(
            executionId, userId, sourceMessageId, originalRequest, clock.instant()));
    }

    @Override
    public AgentExecutionCheckpoint saveExecution(AgentExecutionCheckpoint execution) {
        if (execution == null || execution.getId() == null
            || execution.getId().isBlank()) {
            throw new IllegalArgumentException("执行检查点及executionId不能为空");
        }
        execution.touch(clock.instant());
        return executions.save(execution);
    }

    @Override
    public AgentExecutionSnapshot savePlan(
        String executionId, int planVersion, List<AgentTask> plan
    ) {
        if (planVersion < 1) throw new IllegalArgumentException("planVersion必须大于0");
        List<AgentTask> safePlan = plan == null ? List.of() : List.copyOf(plan);
        ensureUniqueTaskIds(safePlan);
        AgentExecutionCheckpoint execution = executions.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException(
                "找不到Agent执行实例：" + executionId));
        Instant now = clock.instant();
        List<AgentTaskCheckpoint> checkpoints = safePlan.stream().map(task -> {
            String id = AgentTaskCheckpoint.checkpointId(executionId, task.id());
            AgentTaskCheckpoint checkpoint = tasks.findById(id)
                .orElseGet(() -> AgentTaskCheckpoint.fromTask(
                    executionId, planVersion, task, serialize(task), now));
            checkpoint.updateDefinition(planVersion, task, serialize(task), now);
            return checkpoint;
        }).toList();
        tasks.saveAll(checkpoints);
        execution.updatePlan(
            planVersion, safePlan.stream().map(AgentTask::id).toList(), now);
        AgentExecutionCheckpoint savedExecution = executions.save(execution);
        return new AgentExecutionSnapshot(
            savedExecution == null ? execution : savedExecution, checkpoints);
    }

    @Override
    public AgentTaskCheckpoint saveTask(AgentTaskCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getExecutionId() == null
            || checkpoint.getTaskId() == null) {
            throw new IllegalArgumentException("任务检查点、executionId和taskId不能为空");
        }
        checkpoint.setUpdatedAt(clock.instant());
        return tasks.save(checkpoint);
    }

    @Override
    public Optional<AgentExecutionSnapshot> load(String executionId) {
        return executions.findById(executionId).map(execution ->
            new AgentExecutionSnapshot(
                execution, tasks.findByExecutionIdOrderByOrderAsc(executionId)));
    }

    @Override
    public List<AgentExecutionSnapshot> findRecoverableExecutions() {
        return executions.findByStatusInOrderByUpdatedAtAsc(RECOVERABLE_STATUSES)
            .stream()
            .map(execution -> new AgentExecutionSnapshot(
                execution,
                tasks.findByExecutionIdOrderByOrderAsc(execution.getId())))
            .toList();
    }

    @Override
    public boolean tryAcquireRecoveryLease(
        String executionId, String owner, Duration duration
    ) {
        if (mongoTemplate == null) return true;
        Instant now = clock.instant();
        Criteria leaseAvailable = new Criteria().orOperator(
            Criteria.where("leaseExpiresAt").exists(false),
            Criteria.where("leaseExpiresAt").lt(now),
            Criteria.where("leaseOwner").is(owner));
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("_id").is(executionId), leaseAvailable));
        Update update = new Update()
            .set("leaseOwner", owner)
            .set("leaseExpiresAt", now.plus(duration))
            .set("status", AgentCheckpointExecutionStatus.RECOVERING)
            .set("updatedAt", now)
            .set("lastCheckpointAt", now);
        return mongoTemplate.findAndModify(query, update,
            FindAndModifyOptions.options().returnNew(true),
            AgentExecutionCheckpoint.class) != null;
    }

    @Override
    public void releaseRecoveryLease(String executionId, String owner) {
        if (mongoTemplate == null) return;
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("_id").is(executionId),
            Criteria.where("leaseOwner").is(owner)));
        mongoTemplate.updateFirst(query, new Update()
            .set("leaseOwner", "")
            .unset("leaseExpiresAt")
            .set("updatedAt", clock.instant()), AgentExecutionCheckpoint.class);
    }

    @Override
    public List<AgentExecutionCheckpoint> findUndeliveredRecoveryResults() {
        return executions
            .findByRecoveryCompletedAtNotNullAndRecoveryResultDeliveredFalseOrderByRecoveryCompletedAtAsc();
    }

    @Override
    public List<AgentExecutionCheckpoint> findUnnotifiedRecoveryConfirmations() {
        return executions
            .findByStatusAndRecoveryConfirmationNotifiedFalseOrderByUpdatedAtAsc(
                AgentCheckpointExecutionStatus.WAITING_CONFIRMATION);
    }

    @Override
    public AgentTask deserializeTask(AgentTaskCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getTaskJson() == null
            || checkpoint.getTaskJson().isBlank()) {
            throw new IllegalArgumentException("任务检查点没有taskJson");
        }
        try {
            return mapper.readValue(checkpoint.getTaskJson(), AgentTask.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                "任务检查点JSON无法反序列化：" + checkpoint.getTaskId(), error);
        }
    }

    private String serialize(AgentTask task) {
        try {
            return mapper.writeValueAsString(task);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("AgentTask无法序列化：" + task.id(), error);
        }
    }

    private void ensureUniqueTaskIds(List<AgentTask> plan) {
        long unique = plan.stream().map(AgentTask::id).distinct().count();
        if (unique != plan.size()) throw new IllegalArgumentException("任务计划包含重复taskId");
    }
}
