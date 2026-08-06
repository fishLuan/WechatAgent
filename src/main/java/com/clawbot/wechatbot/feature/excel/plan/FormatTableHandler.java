package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格式化操作：按参数设置标题行/冻结首行/自动筛选（持久化到表格，每次导出自动应用）。
 * 设置字段 → 快照（描述「设置格式」）→ 先导出再保存（导出失败不落库）；
 * 回复按实际应用项拼接（如「标题/冻结首行/自动筛选」）。
 */
public final class FormatTableHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public FormatTableHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.FORMAT_TABLE;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        List<String> applied = new ArrayList<>();
        // 标题行（可空参数：未指定时保持原样）
        String title = operation.param("title");
        if (title != null && !title.isBlank()) {
            table.setTitleRow(title);
            applied.add("标题");
        }
        if ("true".equals(operation.param("freezeHeader"))) {
            table.setFreezeHeader(true);
            applied.add("冻结首行");
        }
        if ("true".equals(operation.param("autoFilter"))) {
            table.setAutoFilter(true);
            applied.add("自动筛选");
        }
        if (applied.isEmpty()) {
            return OperationResult.failure(
                "没有可应用的格式设置，请指定「加标题 X」「冻结首行」或「加筛选」。");
        }
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "设置格式");
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已应用格式：" + String.join("/", applied) + "。", bytes);
    }
}
