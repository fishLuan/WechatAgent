package com.clawbot.wechatbot.confirmation;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "pending_confirmation")
public class PendingConfirmation {
    @Id private String id;
    @Indexed private String userId;
    private Long sourceMessageId;
    private String toolName;
    private String argumentsJson;
    private String operationSummary;
    private String riskLevel;
    private ConfirmationStatus status;
    private String result;
    private String modification;
    private long createdAt;
    @Indexed(expireAfter = "0s") private java.util.Date expiresAt;
    private long updatedAt;

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
    public Long getSourceMessageId() { return sourceMessageId; } public void setSourceMessageId(Long v) { sourceMessageId = v; }
    public String getToolName() { return toolName; } public void setToolName(String v) { toolName = v; }
    public String getArgumentsJson() { return argumentsJson; } public void setArgumentsJson(String v) { argumentsJson = v; }
    public String getOperationSummary() { return operationSummary; } public void setOperationSummary(String v) { operationSummary = v; }
    public String getRiskLevel() { return riskLevel; } public void setRiskLevel(String v) { riskLevel = v; }
    public ConfirmationStatus getStatus() { return status; } public void setStatus(ConfirmationStatus v) { status = v; }
    public String getResult() { return result; } public void setResult(String v) { result = v; }
    public String getModification() { return modification; } public void setModification(String v) { modification = v; }
    public long getCreatedAt() { return createdAt; } public void setCreatedAt(long v) { createdAt = v; }
    public java.util.Date getExpiresAt() { return expiresAt; } public void setExpiresAt(java.util.Date v) { expiresAt = v; }
    public long getUpdatedAt() { return updatedAt; } public void setUpdatedAt(long v) { updatedAt = v; }
}
