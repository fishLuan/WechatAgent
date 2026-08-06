package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 去重操作：按整行或指定列去重，保留首次出现的顺序（与删除行互斥，不冲突）。
 */
public final class DeduplicateHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public DeduplicateHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.DEDUPLICATE;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        String column = operation.param("column");
        boolean wholeRow = column == null || column.isBlank();
        int columnIndex = wholeRow
            ? -1 : ExcelService.findColumnIndex(table.getHeaders(), column);
        if (!wholeRow && columnIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + column + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, wholeRow ? "整行去重" : "按" + column + "去重");
        // 保留首次出现：set 记录已见过的整行/列值
        Set<Object> seen = new HashSet<>();
        List<List<String>> kept = new ArrayList<>();
        for (List<String> cells : table.getRows()) {
            Object key = wholeRow ? cells : cellValue(cells, columnIndex);
            if (seen.add(key)) {
                kept.add(cells);
            }
        }
        int removed = table.getRows().size() - kept.size();
        table.setRows(kept);
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已删除 " + removed + " 行重复数据（剩 " + kept.size() + " 行）。", bytes);
    }

    private static String cellValue(List<String> cells, int columnIndex) {
        return columnIndex < cells.size() ? cells.get(columnIndex) : "";
    }
}
