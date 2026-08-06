package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分组汇总操作：按分组列聚合数值列（SUM/AVERAGE 用 BigDecimal，MAX/MIN 数值比较，COUNT 计行数）；
 * 结果新建一张「原表名-汇总」的独立表，原表保留、不切换当前表；includeRatio=true 时追加「占比」列。
 */
public final class GroupSummaryHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public GroupSummaryHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.GROUP_SUMMARY;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        String groupColumn = operation.param("groupColumn");
        String valueColumn = operation.param("valueColumn");
        ExcelService.QueryType type = ExcelService.QueryType.valueOf(operation.param("aggregate"));
        boolean includeRatio = "true".equals(operation.param("includeRatio"));
        int groupIndex = ExcelService.findColumnIndex(table.getHeaders(), groupColumn);
        if (groupIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + groupColumn + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        boolean countOnly = valueColumn == null || valueColumn.isBlank();
        int valueIndex = countOnly
            ? -1 : ExcelService.findColumnIndex(table.getHeaders(), valueColumn);
        if (!countOnly && valueIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + valueColumn + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        // 分组统计：LinkedHashMap 保持分组首次出现顺序
        Map<String, GroupStat> groups = new LinkedHashMap<>();
        for (List<String> cells : table.getRows()) {
            String key = cellValue(cells, groupIndex);
            GroupStat stat = groups.computeIfAbsent(key, k -> new GroupStat());
            stat.rowCount++;
            if (valueIndex >= 0) {
                BigDecimal decimal = ExcelService.parseDecimal(cellValue(cells, valueIndex));
                if (decimal != null) {
                    stat.sum = stat.sum.add(decimal);
                    stat.numericCount++;
                    double number = decimal.doubleValue();
                    if (stat.max == null || number > stat.max) stat.max = number;
                    if (stat.min == null || number < stat.min) stat.min = number;
                }
            }
        }
        // 汇总表头：分组列 + 聚合列（COUNT 且未指定数值列时列名为「行数」）+ 可选占比
        String aggregateColumnName = countOnly
            ? "行数" : valueColumn + "(" + aggregateLabel(type) + ")";
        List<String> summaryHeaders = new ArrayList<>(List.of(groupColumn, aggregateColumnName));
        if (includeRatio) {
            summaryHeaders.add("占比");
        }
        // 占比分母为各分组值的总和（校验器已保证占比只出现在 SUM 时）
        BigDecimal total = BigDecimal.ZERO;
        if (type == ExcelService.QueryType.SUM) {
            for (GroupStat stat : groups.values()) {
                total = total.add(stat.sum);
            }
        }
        List<List<String>> summaryRows = new ArrayList<>();
        for (Map.Entry<String, GroupStat> entry : groups.entrySet()) {
            List<String> row = new ArrayList<>();
            row.add(entry.getKey());
            row.add(formatValue(entry.getValue(), type));
            if (includeRatio) {
                row.add(formatRatio(entry.getValue().sum, total));
            }
            summaryRows.add(row);
        }
        // 汇总结果新建一张独立表（原表保留、不切换当前表），标题「原表名-汇总」
        ExcelTable summaryTable = new ExcelTable(userId, table.getTitle() + "-汇总");
        summaryTable.setHeaders(summaryHeaders);
        summaryTable.setRows(summaryRows);
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(summaryTable);
        excelService.save(summaryTable);
        return OperationResult.success(
            "✅ 已生成汇总表「" + summaryTable.getTitle()
                + "」，原表保持不变（发送「切换表格：" + summaryTable.getTitle()
                + "」查看）。", bytes);
    }

    /** 聚合列名后缀：合计/平均/最大/最小/计数。 */
    private static String aggregateLabel(ExcelService.QueryType type) {
        return switch (type) {
            case SUM -> "合计";
            case AVERAGE -> "平均";
            case MAX -> "最大";
            case MIN -> "最小";
            case COUNT -> "计数";
        };
    }

    /** 聚合值格式化：SUM/AVERAGE 保留 2 位小数；MAX/MIN 取整；COUNT 计行数。 */
    private static String formatValue(GroupStat stat, ExcelService.QueryType type) {
        return switch (type) {
            case SUM -> String.format(Locale.ROOT, "%.2f", stat.sum);
            case AVERAGE -> stat.numericCount == 0 ? "0.00" : String.format(Locale.ROOT, "%.2f",
                stat.sum.divide(BigDecimal.valueOf(stat.numericCount), 2, RoundingMode.HALF_UP));
            case MAX -> stat.max == null ? "0" : String.valueOf(Math.round(stat.max));
            case MIN -> stat.min == null ? "0" : String.valueOf(Math.round(stat.min));
            case COUNT -> String.valueOf(stat.rowCount);
        };
    }

    /** 占比格式化：该组值/总值×100，保留 2 位小数；总值为 0 时统一 0.00。 */
    private static String formatRatio(BigDecimal groupSum, BigDecimal total) {
        if (total.signum() == 0) return "0.00";
        return String.format(Locale.ROOT, "%.2f",
            groupSum.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP));
    }

    private static String cellValue(List<String> cells, int columnIndex) {
        return columnIndex < cells.size() ? cells.get(columnIndex) : "";
    }

    /** 单个分组的聚合累加状态。 */
    private static final class GroupStat {
        BigDecimal sum = BigDecimal.ZERO;
        int numericCount = 0;
        int rowCount = 0;
        Double max = null;
        Double min = null;
    }
}
