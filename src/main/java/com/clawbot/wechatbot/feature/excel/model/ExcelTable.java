package com.clawbot.wechatbot.feature.excel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 按微信用户隔离的 Excel 表格状态（Mongo 持久化，重启不丢失）。 */
@Document(collection = "excel_table")
public class ExcelTable {
    @Id
    private String id;
    @Indexed(unique = true)
    private String wechatUserId;
    private String title;
    private List<String> headers = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
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
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
