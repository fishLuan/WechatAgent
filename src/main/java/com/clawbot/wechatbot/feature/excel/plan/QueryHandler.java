package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 列聚合查询操作：纯文字回复，不导出附件（业务逻辑与重构前 tryQuery 一致）。 */
public final class QueryHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public QueryHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.QUERY;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        String column = operation.param("column");
        ExcelService.QueryType type =
            ExcelService.QueryType.valueOf(operation.param("queryType"));
        return OperationResult.success(excelService.queryColumn(table, column, type));
    }
}
