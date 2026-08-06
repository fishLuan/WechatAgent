package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/**
 * 缺失值补全操作：把指定列中的空字符串单元格填充为固定值（默认「未知」）。
 */
public final class FillMissingHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public FillMissingHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.FILL_MISSING;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        String column = operation.param("column");
        int columnIndex = ExcelService.findColumnIndex(table.getHeaders(), column);
        if (columnIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + column + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        String value = operation.param("value");
        String fillValue = value == null || value.isBlank() ? "未知" : value;
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "补全" + column + "列");
        int filled = 0;
        for (List<String> cells : table.getRows()) {
            if (columnIndex < cells.size()
                && (cells.get(columnIndex) == null || cells.get(columnIndex).isEmpty())) {
                cells.set(columnIndex, fillValue);
                filled++;
            }
        }
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已补全" + column + "列 " + filled + " 个空值。", bytes);
    }
}
