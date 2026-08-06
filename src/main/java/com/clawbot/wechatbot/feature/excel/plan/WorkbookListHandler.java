package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.List;

/** 查看工作簿列表：数量 + 逐条标题（活动表标注「（当前）」；纯文字回复，不导出附件）。 */
public final class WorkbookListHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public WorkbookListHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.WORKBOOK_LIST;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        List<ExcelTable> tables = excelService.listWorkbooks(userId);
        if (tables.isEmpty()) {
            return OperationResult.success(
                "📋 你还没有表格，可以发送「新建表格 名字」创建，或上传 xlsx / 发带'表格'字样的截图导入。");
        }
        ExcelTable active = excelService.getActiveWorkbook(userId);
        StringBuilder reply = new StringBuilder("📋 共 " + tables.size() + " 张表格：");
        for (ExcelTable workbook : tables) {
            reply.append("\n· ").append(workbook.getTitle());
            if (active != null && active.getId().equals(workbook.getId())) {
                reply.append("（当前）");
            }
        }
        return OperationResult.success(reply.toString());
    }
}
