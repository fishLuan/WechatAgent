package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.ArrayList;
import java.util.List;

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
        String extraCharts = operation.param("extraCharts");
        if (extraCharts != null && !extraCharts.isBlank()) {
            return handleMultiChart(table, chartType, categoryColumn, valueColumn, extraCharts);
        }
        // 不修改表格数据、不写快照：图表在导出时呈现
        byte[] bytes = excelService.toXlsxWithChart(
            table, chartType, categoryColumn, valueColumn);
        return OperationResult.success(
            "✅ 已生成" + chartTypeLabel(chartType) + "（按 " + categoryColumn
                + " 统计 " + valueColumn + "），图表在'图表'工作表。",
            bytes);
    }

    /** 多图表：extraCharts 为「类型|分类|数值」用 | 连接，每张图各占一个工作表。 */
    private OperationResult handleMultiChart(ExcelTable table, String chartType,
                                             String categoryColumn, String valueColumn,
                                             String extraCharts) throws Exception {
        List<ExcelService.ChartSpec> specs = new ArrayList<>();
        specs.add(new ExcelService.ChartSpec(chartType, categoryColumn, valueColumn));
        String[] parts = extraCharts.split("\\|");
        for (int i = 0; i + 2 < parts.length; i += 3) {
            String type = parts[i].trim();
            String cat = parts[i + 1].trim();
            String val = parts[i + 2].trim();
            int categoryIndex = ExcelService.findColumnIndex(table.getHeaders(), cat);
            if (categoryIndex < 0) {
                return OperationResult.failure(
                    "❌ 找不到列「" + cat + "」，现有列：" + String.join("、", table.getHeaders()));
            }
            if (ExcelService.findColumnIndex(table.getHeaders(), val) < 0) {
                return OperationResult.failure(
                    "❌ 找不到列「" + val + "」，现有列：" + String.join("、", table.getHeaders()));
            }
            specs.add(new ExcelService.ChartSpec(type, cat, val));
        }
        byte[] bytes = excelService.toXlsxWithCharts(table, specs);
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) summary.append("、");
            ExcelService.ChartSpec spec = specs.get(i);
            summary.append(chartTypeLabel(spec.chartType()))
                .append("（").append(spec.categoryColumn()).append("/")
                .append(spec.valueColumn()).append("）");
        }
        return OperationResult.success(
            "✅ 已生成 " + specs.size() + " 张图表：" + summary
                + "，位于'图表'等工作表。", bytes);
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
