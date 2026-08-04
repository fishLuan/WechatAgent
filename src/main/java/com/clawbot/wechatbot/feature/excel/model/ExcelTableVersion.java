package com.clawbot.wechatbot.feature.excel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 表格历史版本快照（独立 collection，每张表最多保留上限条数）。 */
@Document(collection = "excel_table_version")
public class ExcelTableVersion {
    @Id
    private String id;
    /** 对应 ExcelTable.id。 */
    private String tableId;
    private List<String> headers = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
    /** 操作说明，如「添加第N行」「回滚操作」。 */
    private String description;
    private Instant createdAt;

    public ExcelTableVersion() {
    }

    public ExcelTableVersion(String tableId, List<String> headers,
                             List<List<String>> rows, String description) {
        this.tableId = tableId;
        setHeaders(headers);
        setRows(rows);
        this.description = description;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTableId() { return tableId; }
    public void setTableId(String value) { this.tableId = value; }
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
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
}
