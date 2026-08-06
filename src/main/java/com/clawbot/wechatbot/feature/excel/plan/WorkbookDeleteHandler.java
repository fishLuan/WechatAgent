package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/** 删除工作簿：删除表及其版本快照；删除的是活动表时说明已无当前表。 */
public final class WorkbookDeleteHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public WorkbookDeleteHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.WORKBOOK_DELETE;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        String name = operation.param("name");
        List<ExcelTable> tables = excelService.listWorkbooks(userId);
        ExcelTable target = OperationChecks.findWorkbookByName(tables, name);
        if (target == null) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        ExcelTable active = excelService.getActiveWorkbook(userId);
        boolean wasActive = active != null && active.getId().equals(target.getId());
        if (!excelService.deleteWorkbook(userId, target.getId())) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        if (wasActive) {
            return OperationResult.success("✅ 已删除表格「" + name + "」。「" + name
                + "」是当前活动表格，已无当前表，可发送「新建表格 名字」创建新表。");
        }
        return OperationResult.success("✅ 已删除表格「" + name + "」。");
    }
}
