package com.clawbot.wechatbot.service.agent.interrupt;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection = "agent_run")
public class AgentRunRecord {
    @Id private String id;
    @Indexed private String userId;
    private Long sourceMessageId;
    private String request;
    private AgentRunStatus status;
    private List<String> completedTasks = List.of();
    private List<String> cancelledTasks = List.of();
    private boolean sideEffectsCompleted;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
    public Long getSourceMessageId() { return sourceMessageId; } public void setSourceMessageId(Long v) { sourceMessageId = v; }
    public String getRequest() { return request; } public void setRequest(String v) { request = v; }
    public AgentRunStatus getStatus() { return status; } public void setStatus(AgentRunStatus v) { status = v; }
    public List<String> getCompletedTasks() { return completedTasks; } public void setCompletedTasks(List<String> v) { completedTasks = v == null ? List.of() : List.copyOf(v); }
    public List<String> getCancelledTasks() { return cancelledTasks; } public void setCancelledTasks(List<String> v) { cancelledTasks = v == null ? List.of() : List.copyOf(v); }
    public boolean isSideEffectsCompleted() { return sideEffectsCompleted; } public void setSideEffectsCompleted(boolean v) { sideEffectsCompleted = v; }
    public long getCreatedAt() { return createdAt; } public void setCreatedAt(long v) { createdAt = v; }
    public long getUpdatedAt() { return updatedAt; } public void setUpdatedAt(long v) { updatedAt = v; }
}
