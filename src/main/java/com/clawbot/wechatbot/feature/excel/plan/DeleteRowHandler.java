package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/** 删除行操作：变更前快照、删除指定行（业务逻辑与重构前 deleteRow 一致）。 */
public final class DeleteRowHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public DeleteRowHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.DELETE_ROW;
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
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "删除第" + rowNumber + "行");
        List<String> removed = table.getRows().remove(index);
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已删除第 " + rowNumber + " 行（" + String.join("、", removed) + "）。", bytes);
    }
}
