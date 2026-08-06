package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;
import java.util.Optional;

/** 复制工作簿：复制表（新 id、标题加「副本」、不含版本历史）并切换为当前表。 */
public final class WorkbookCopyHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public WorkbookCopyHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.WORKBOOK_COPY;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        String name = operation.param("name");
        List<ExcelTable> tables = excelService.listWorkbooks(userId);
        ExcelTable target = OperationChecks.findWorkbookByName(tables, name);
        if (target == null) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        Optional<ExcelTable> copy = excelService.copyWorkbook(userId, target.getId());
        if (copy.isEmpty()) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        return OperationResult.success("✅ 已复制表格「" + name + "」为「"
            + copy.get().getTitle() + "」，并切换为当前表格");
    }
}
