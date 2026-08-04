package com.clawbot.wechatbot.feature.excel.plan;

/** 表格操作类型：与指令路由的十一类操作一一对应。 */
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
    VERSION_HISTORY("版本历史");

    private final String label;

    ExcelOperationType(String label) {
        this.label = label;
    }

    /** 中文说明，用于校验错误提示。 */
    public String label() {
        return label;
    }
}
