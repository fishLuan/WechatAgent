package com.clawbot.wechatbot.service.agent.checkpoint;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "agent_execution_checkpoint")
public class AgentExecutionCheckpoint {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Id private String id;
    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    @Indexed private String userId;
    private Long sourceMessageId;
    private String originalRequest = "";
    @Indexed private AgentCheckpointExecutionStatus status =
        AgentCheckpointExecutionStatus.CREATED;
    private int currentRound;
    private int replanCount;
    private int totalTaskExecutions;
    private int planVersion;
    private List<String> taskIds = new ArrayList<>();
    private List<String> completedTaskIds = new ArrayList<>();
    private List<String> cancelledTaskIds = new ArrayList<>();
    private boolean sideEffectsCompleted;
    private String leaseOwner = "";
    @Indexed private Instant leaseExpiresAt;
    private String failureCode = "";
    private String failureMessage = "";
    private String recoveryResultText = "";
    private Instant recoveryCompletedAt;
    @Indexed private boolean recoveryResultDelivered;
    @Indexed private boolean recoveryConfirmationNotified;
    private Instant createdAt;
    @Indexed private Instant updatedAt;
    private Instant lastCheckpointAt;
    @Version private Long version;

    public static AgentExecutionCheckpoint create(
        String executionId, String userId, Long sourceMessageId,
        String originalRequest, Instant now
    ) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId不能为空");
        }
        AgentExecutionCheckpoint checkpoint = new AgentExecutionCheckpoint();
        checkpoint.id = executionId.trim();
        checkpoint.userId = userId == null ? "" : userId.trim();
        checkpoint.sourceMessageId = sourceMessageId;
        checkpoint.originalRequest = originalRequest == null ? "" : originalRequest;
        checkpoint.createdAt = now;
        checkpoint.updatedAt = now;
        checkpoint.lastCheckpointAt = now;
        return checkpoint;
    }

    public void updatePlan(int newPlanVersion, List<String> newTaskIds, Instant now) {
        if (newPlanVersion < planVersion) {
            throw new IllegalArgumentException("planVersion不能回退");
        }
        planVersion = newPlanVersion;
        taskIds = newTaskIds == null ? new ArrayList<>() : new ArrayList<>(newTaskIds);
        touch(now);
    }

    public void touch(Instant now) {
        updatedAt = now;
        lastCheckpointAt = now;
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long value) { sourceMessageId = value; }
    public String getOriginalRequest() { return originalRequest; }
    public void setOriginalRequest(String value) { originalRequest = value; }
    public AgentCheckpointExecutionStatus getStatus() { return status; }
    public void setStatus(AgentCheckpointExecutionStatus value) { status = value; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int value) { currentRound = value; }
    public int getReplanCount() { return replanCount; }
    public void setReplanCount(int value) { replanCount = value; }
    public int getTotalTaskExecutions() { return totalTaskExecutions; }
    public void setTotalTaskExecutions(int value) { totalTaskExecutions = value; }
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int value) { planVersion = value; }
    public List<String> getTaskIds() { return taskIds; }
    public void setTaskIds(List<String> value) { taskIds = copy(value); }
    public List<String> getCompletedTaskIds() { return completedTaskIds; }
    public void setCompletedTaskIds(List<String> value) { completedTaskIds = copy(value); }
    public List<String> getCancelledTaskIds() { return cancelledTaskIds; }
    public void setCancelledTaskIds(List<String> value) { cancelledTaskIds = copy(value); }
    public boolean isSideEffectsCompleted() { return sideEffectsCompleted; }
    public void setSideEffectsCompleted(boolean value) { sideEffectsCompleted = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant value) { leaseExpiresAt = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String value) { failureMessage = value; }
    public String getRecoveryResultText() { return recoveryResultText; }
    public void setRecoveryResultText(String value) {
        recoveryResultText = value == null ? "" : value;
    }
    public Instant getRecoveryCompletedAt() { return recoveryCompletedAt; }
    public void setRecoveryCompletedAt(Instant value) { recoveryCompletedAt = value; }
    public boolean isRecoveryResultDelivered() { return recoveryResultDelivered; }
    public void setRecoveryResultDelivered(boolean value) {
        recoveryResultDelivered = value;
    }
    public boolean isRecoveryConfirmationNotified() {
        return recoveryConfirmationNotified;
    }
    public void setRecoveryConfirmationNotified(boolean value) {
        recoveryConfirmationNotified = value;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Instant getLastCheckpointAt() { return lastCheckpointAt; }
    public void setLastCheckpointAt(Instant value) { lastCheckpointAt = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
