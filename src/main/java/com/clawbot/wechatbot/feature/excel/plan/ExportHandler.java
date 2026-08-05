package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 导出表格：把当前活动表完整导出为 xlsx 附件（不修改数据、不快照、不落库）。 */
public final class ExportHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public ExportHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.EXPORT;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        if (table == null || table.getHeaders().isEmpty()) {
            return OperationResult.failure(
                "表格还没有数据，请先创建/导入表格，或添加内容后再导出。");
        }
        byte[] bytes = excelService.toXlsx(table);
        return OperationResult.success(
            "✅ 已导出当前表格（" + table.getHeaders().size() + "列×"
                + table.getRows().size() + "行）：" + table.getTitle(), bytes);
    }
}
