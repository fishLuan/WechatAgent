package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 新建工作簿：新建一张表并设为当前活动表（纯文字回复，不导出附件、不快照）。 */
public final class WorkbookCreateHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public WorkbookCreateHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.WORKBOOK_CREATE;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        String title = operation.param("title");
        excelService.createWorkbook(userId, title);
        return OperationResult.success("✅ 已新建表格「" + title + "」，并切换为当前表格");
    }
}
