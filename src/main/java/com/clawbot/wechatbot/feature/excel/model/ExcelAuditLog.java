package com.clawbot.wechatbot.feature.excel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** 用户操作审计日志：每次表格操作执行后写一条（Mongo 持久化，按用户最多保留 200 条）。 */
@Document(collection = "excel_audit_log")
public class ExcelAuditLog {
    @Id
    private String id;
    @Indexed
    private String wechatUserId;
    /** 操作涉及的工作簿 id（工作簿管理/操作日志等不涉及具体表时为空）。 */
    private String workbookId;
    /** 操作类型名，复合计划用 + 拼接，如 SORT、SORT+GROUP_SUMMARY。 */
    private String operation;
    /** 操作是否成功。 */
    private boolean success;
    /** 结果文案或失败原因（写入前截断 200 字）。 */
    private String detail;
    private Instant createdAt;

    public ExcelAuditLog() {
    }

    public ExcelAuditLog(String wechatUserId, String workbookId, String operation,
                         boolean success, String detail) {
        this.wechatUserId = wechatUserId;
        this.workbookId = workbookId;
        this.operation = operation;
        this.success = success;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String value) { this.wechatUserId = value; }
    public String getWorkbookId() { return workbookId; }
    public void setWorkbookId(String value) { this.workbookId = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { this.operation = value; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean value) { this.success = value; }
    public String getDetail() { return detail; }
    public void setDetail(String value) { this.detail = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
}
