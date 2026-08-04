package com.clawbot.wechatbot.feature.excel.plan;

/** 表格操作类型：与指令路由的二十类操作一一对应（含知识管理指令与工作簿管理指令）。 */
public enum ExcelOperationType {
    CREATE_TABLE("生成表格"),
    ADD_ROW("添加行"),
    UPDATE_ROW("修改行"),
    DELETE_ROW("删除行"),
    QUERY("列查询"),
    SORT("排序"),
    DEDUPLICATE("去重"),
    GROUP_SUMMARY("分组汇总"),
    FILL_MISSING("缺失补全"),
    ROLLBACK("回滚"),
    VERSION_HISTORY("版本历史"),
    WORKBOOK_CREATE("新建工作簿"),
    WORKBOOK_LIST("工作簿列表"),
    WORKBOOK_SELECT("选择工作簿"),
    WORKBOOK_RENAME("重命名工作簿"),
    WORKBOOK_DELETE("删除工作簿"),
    WORKBOOK_COPY("复制工作簿"),
    KNOWLEDGE_ADD("添加知识"),
    KNOWLEDGE_LIST("查看知识"),
    KNOWLEDGE_DELETE("删除知识");

    private final String label;

    ExcelOperationType(String label) {
        this.label = label;
    }

    /** 中文说明，用于校验错误提示。 */
    public String label() {
        return label;
    }
}
