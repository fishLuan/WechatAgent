package com.clawbot.wechatbot.feature.excel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** 用户工作簿状态：记录当前活动表格（Mongo 持久化，重启不丢失；一个用户一条）。 */
@Document(collection = "excel_user_state")
public class ExcelUserState {
    @Id
    private String id;
    @Indexed(unique = true)
    private String wechatUserId;
    /** 当前活动工作簿（对应 ExcelTable.id）；无活动表时为空。 */
    private String activeWorkbookId;

    public ExcelUserState() {
    }

    public ExcelUserState(String wechatUserId) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("wechatUserId 不能为空");
        }
        this.wechatUserId = wechatUserId.trim();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWechatUserId() { return wechatUserId; }
    public void setWechatUserId(String value) { this.wechatUserId = value; }
    public String getActiveWorkbookId() { return activeWorkbookId; }
    public void setActiveWorkbookId(String value) { this.activeWorkbookId = value; }
}
