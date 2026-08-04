package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/**
 * 图表操作：按分类列/数值列生成柱状/折线/饼图（新增「图表」工作表）。
 * 只校验列存在性与数值量，不修改表格数据、不写快照（图表是导出时呈现）；
 * 数值列数值不足时失败并提示确认列。
 */
public final class ChartHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public ChartHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.CHART;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        String chartType = operation.param("chartType");
        String categoryColumn = operation.param("categoryColumn");
        String valueColumn = operation.param("valueColumn");
        // 列存在校验：分类列/数值列
        int categoryIndex = ExcelService.findColumnIndex(table.getHeaders(), categoryColumn);
        if (categoryIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + categoryColumn + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        int valueIndex = ExcelService.findColumnIndex(table.getHeaders(), valueColumn);
        if (valueIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + valueColumn + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        // 数值列数值不足（少于 2 条）时失败：图表至少需要两个数据点
        long numericCount = table.getRows().stream()
            .filter(row -> valueIndex < row.size()
                && ExcelService.parseNumber(row.get(valueIndex)) != null)
            .count();
        if (numericCount < 2) {
            return OperationResult.failure("图表数据不足，请确认分类列和数值列。");
        }
        // 不修改表格数据、不写快照：图表在导出时呈现
        byte[] bytes = excelService.toXlsxWithChart(
            table, chartType, categoryColumn, valueColumn);
        return OperationResult.success(
            "✅ 已生成" + chartTypeLabel(chartType) + "（按 " + categoryColumn
                + " 统计 " + valueColumn + "），图表在'图表'工作表。",
            bytes);
    }

    /** 图表类型枚举名 → 中文说明。 */
    private static String chartTypeLabel(String chartType) {
        return switch (chartType) {
            case "BAR" -> "柱状图";
            case "LINE" -> "折线图";
            default -> "饼图";
        };
    }
}
