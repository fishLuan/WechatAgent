package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/**
 * 汇总页操作：导出时新增「汇总」工作表（表标题 + 列数/行数 + 每列数值型合计与平均）。
 * 不修改表格数据、不写快照（汇总页是导出时呈现）。
 */
public final class DashboardHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public DashboardHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.DASHBOARD;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        byte[] bytes = excelService.toXlsxWithDashboard(table);
        return OperationResult.success("✅ 已生成汇总页。", bytes);
    }
}
