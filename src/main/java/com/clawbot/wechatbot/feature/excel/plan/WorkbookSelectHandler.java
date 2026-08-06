package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/** 选择工作簿：按标题匹配（精确优先、无则包含），命中后切换为活动表；找不到给出现有列表。 */
public final class WorkbookSelectHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public WorkbookSelectHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.WORKBOOK_SELECT;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        String name = operation.param("name");
        List<ExcelTable> tables = excelService.listWorkbooks(userId);
        ExcelTable target = OperationChecks.findWorkbookByName(tables, name);
        if (target == null) {
            return OperationResult.failure(OperationChecks.workbookNotFoundMessage(name, tables));
        }
        // 重名提示：存在多张同名表格时明确说明选择的是第一张
        long duplicateCount = tables.stream()
            .filter(workbook -> name.equals(workbook.getTitle())).count();
        excelService.setActiveWorkbook(userId, target);
        String reply = "✅ 已切换到表格「" + target.getTitle() + "」。";
        if (duplicateCount > 1) {
            reply += "（存在 " + duplicateCount + " 张同名表格，已选择第一张）";
        }
        return OperationResult.success(reply);
    }
}
