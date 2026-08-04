package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/** 添加行操作：变更前快照、按表头对齐拆分单元格（业务逻辑与重构前 addRow 一致）。 */
public final class AddRowHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public AddRowHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.ADD_ROW;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        List<String> cells = ExcelService.splitRowData(operation.param("cells"), table);
        if (cells.isEmpty()) {
            return OperationResult.failure("添加的数据行为空。");
        }
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "添加第" + (table.getRows().size() + 1) + "行");
        table.getRows().add(cells);
        // 先导出再保存：导出失败（如公式错误）时不落库，避免"取消导出但数据已变更"
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已添加第 " + table.getRows().size() + " 行。", bytes);
    }
}
