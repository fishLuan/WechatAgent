package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "agent_task_checkpoint")
@CompoundIndex(
    name = "uk_agent_task_checkpoint_execution_task",
    def = "{'executionId': 1, 'taskId': 1}",
    unique = true
)
public class AgentTaskCheckpoint {
    @Id private String id;
    @Indexed private String executionId;
    private String taskId;
    private int planVersion;
    private int order;
    private AgentTaskType type;
    private String skillName = "";
    private String instruction = "";
    private String taskJson = "{}";
    private String resolvedInputJson = "{}";
    private TaskStatus status = TaskStatus.PENDING;
    private int attemptCount;
    private int replanGeneration;
    private String resultJson = "";
    private String evaluationJson = "";
    private String verifiedOutputJson = "{}";
    @Indexed private String idempotencyKey = "";
    private boolean sideEffect;
    private String errorCode = "";
    private String errorMessage = "";
    private Instant startedAt;
    private Instant completedAt;
    @Indexed private Instant updatedAt;
    @Version private Long version;

    public static String checkpointId(String executionId, String taskId) {
        return executionId + ":" + taskId;
    }

    public static AgentTaskCheckpoint fromTask(
        String executionId, int planVersion, AgentTask task,
        String taskJson, Instant now
    ) {
        AgentTaskCheckpoint checkpoint = new AgentTaskCheckpoint();
        checkpoint.id = checkpointId(executionId, task.id());
        checkpoint.executionId = executionId;
        checkpoint.taskId = task.id();
        checkpoint.updateDefinition(planVersion, task, taskJson, now);
        return checkpoint;
    }

    public void updateDefinition(
        int newPlanVersion, AgentTask task, String serializedTask, Instant now
    ) {
        planVersion = newPlanVersion;
        order = task.order();
        type = task.type();
        skillName = task.skillName();
        instruction = task.instruction();
        taskJson = serializedTask;
        updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { executionId = value; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = value; }
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int value) { planVersion = value; }
    public int getOrder() { return order; }
    public void setOrder(int value) { order = value; }
    public AgentTaskType getType() { return type; }
    public void setType(AgentTaskType value) { type = value; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String value) { skillName = value; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String value) { instruction = value; }
    public String getTaskJson() { return taskJson; }
    public void setTaskJson(String value) { taskJson = value; }
    public String getResolvedInputJson() { return resolvedInputJson; }
    public void setResolvedInputJson(String value) { resolvedInputJson = value; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus value) { status = value; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int value) { attemptCount = value; }
    public int getReplanGeneration() { return replanGeneration; }
    public void setReplanGeneration(int value) { replanGeneration = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public String getEvaluationJson() { return evaluationJson; }
    public void setEvaluationJson(String value) { evaluationJson = value; }
    public String getVerifiedOutputJson() { return verifiedOutputJson; }
    public void setVerifiedOutputJson(String value) { verifiedOutputJson = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public boolean isSideEffect() { return sideEffect; }
    public void setSideEffect(boolean value) { sideEffect = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
