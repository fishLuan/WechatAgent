package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/** 修改行操作：变更前快照、按表头对齐拆分新数据（业务逻辑与重构前 updateRow 一致）。 */
public final class UpdateRowHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public UpdateRowHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.UPDATE_ROW;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        int rowNumber = Integer.parseInt(operation.param("rowNumber"));
        int index = rowNumber - 1;
        if (index < 0 || index >= table.getRows().size()) {
            return OperationResult.failure(
                "行号超出范围，当前共 " + table.getRows().size() + " 行。");
        }
        String newData = operation.param("cells");
        if (newData == null || newData.isBlank()) {
            return OperationResult.failure("缺少新数据，格式示例：修改第2行为 张三,25,北京。");
        }
        List<String> cells = ExcelService.splitRowData(newData, table);
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "修改第" + rowNumber + "行");
        table.getRows().set(index, cells);
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已修改第 " + rowNumber + " 行。", bytes);
    }
}
