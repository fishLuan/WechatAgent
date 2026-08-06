package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;
import java.util.Optional;

/** 重命名工作簿：按标题匹配后改标题（活动状态记录的是表 id，改名不影响当前表）。 */
public final class WorkbookRenameHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public WorkbookRenameHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.WORKBOOK_RENAME;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        String name = operation.param("name");
        String newTitle = operation.param("newTitle");
        List<ExcelTable> tables = excelService.listWorkbooks(userId);
        ExcelTable target = OperationChecks.findWorkbookByName(tables, name);
        if (target == null) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        Optional<ExcelTable> renamed =
            excelService.renameWorkbook(userId, target.getId(), newTitle);
        if (renamed.isEmpty()) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        return OperationResult.success("✅ 已重命名表格「" + name + "」为「" + newTitle + "」。");
    }
}
