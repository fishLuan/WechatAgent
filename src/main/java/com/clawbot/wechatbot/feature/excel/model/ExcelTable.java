package com.clawbot.wechatbot.feature.excel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 按微信用户隔离的 Excel 表格状态（Mongo 持久化，重启不丢失；一个用户可有多张表）。 */
@Document(collection = "excel_table")
public class ExcelTable {
    @Id
    private String id;
    @Indexed
    private String wechatUserId;
    private String title;
    private List<String> headers = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
    /** 表标题行文本（可空）：导出时第 0 行为合并单元格标题，表头行从第 1 行开始。 */
    private String titleRow;
    /** 是否冻结表头行（导出时 createFreezePane）。 */
    private boolean freezeHeader;
    /** 是否对表头+数据范围启用自动筛选（导出时 setAutoFilter）。 */
    private boolean autoFilter;
    private Instant createdAt;
    private Instant updatedAt;

    public ExcelTable() {
    }

    public ExcelTable(String wechatUserId, String title) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("wechatUserId 不能为空");
        }
        this.wechatUserId = wechatUserId.trim();
        this.title = title == null || title.isBlank() ? "未命名表格" : title.trim();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String value) { this.wechatUserId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) {
        this.title = value == null || value.isBlank() ? "未命名表格" : value.trim();
    }
    public List<String> getHeaders() {
        if (headers == null) headers = new ArrayList<>();
        return headers;
    }
    public void setHeaders(List<String> values) {
        headers = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
    public List<List<String>> getRows() {
        if (rows == null) rows = new ArrayList<>();
        return rows;
    }
    public void setRows(List<List<String>> values) {
        rows = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
    public String getTitleRow() { return titleRow; }
    public void setTitleRow(String value) {
        this.titleRow = value == null || value.isBlank() ? null : value.trim();
    }
    public boolean isFreezeHeader() { return freezeHeader; }
    public void setFreezeHeader(boolean value) { this.freezeHeader = value; }
    public boolean isAutoFilter() { return autoFilter; }
    public void setAutoFilter(boolean value) { this.autoFilter = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
