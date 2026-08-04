package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 各操作处理器与校验器共用的表格工具方法。 */
final class OperationChecks {

    private OperationChecks() {
    }

    /** 表格未生成时抛出 IllegalArgumentException（错误文案与重构前 requireTable 一致）。 */
    static void requireTable(ExcelTable table) {
        if (table.getHeaders().isEmpty()) {
            throw new IllegalArgumentException("还没有生成表格，请先提供表头和数据生成表格。");
        }
    }

    /** 表格是否已有非空数据（表头和至少一行数据都齐全才视为有数据）。 */
    static boolean hasData(ExcelTable table) {
        return !table.getHeaders().isEmpty() && !table.getRows().isEmpty();
    }

    /** 由 CREATE_TABLE 参数重建表格文本（表头行 + 换行 + 数据行），供解析/校验复用。 */
    static String rebuildContent(ExcelOperation operation) {
        String headers = operation.param("headers");
        String rows = operation.param("rows");
        return rows == null || rows.isBlank() ? headers : headers + "\n" + rows;
    }
}
