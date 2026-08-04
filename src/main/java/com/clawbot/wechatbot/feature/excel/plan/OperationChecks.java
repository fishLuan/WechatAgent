package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

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

    /** 按标题匹配工作簿：精确优先、无则包含；重名取第一个（选择/重命名/删除/复制共用）。 */
    static ExcelTable findWorkbookByName(List<ExcelTable> tables, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ExcelTable table : tables) {
            if (name.equals(table.getTitle())) {
                return table;
            }
        }
        for (ExcelTable table : tables) {
            if (table.getTitle().contains(name) || name.contains(table.getTitle())) {
                return table;
            }
        }
        return null;
    }

    /** 「找不到表格」统一提示：附现有表格列表，引导先建表。 */
    static String workbookNotFoundMessage(String name, List<ExcelTable> tables) {
        return "❌ 找不到表格「" + name + "」，现有表格："
            + (tables.isEmpty() ? "（还没有表格，可先发送「新建表格 名字」创建）"
                : workbookTitleList(tables)) + "。";
    }

    /** 现有表格标题列表（顿号连接）。 */
    static String workbookTitleList(List<ExcelTable> tables) {
        return tables.stream().map(ExcelTable::getTitle)
            .collect(java.util.stream.Collectors.joining("、"));
    }
}
