package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 生成表格操作：覆盖前快照、替换表头与数据（业务逻辑与重构前 createTable 一致）。 */
public final class CreateTableHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public CreateTableHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.CREATE_TABLE;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        ExcelService.ParsedTable parsed =
            ExcelService.parseTableText(OperationChecks.rebuildContent(operation));
        if (parsed.headers().isEmpty()) {
            return OperationResult.failure(
                "没有可用的表格数据，请提供首行为表头、每行一条的表格内容。");
        }
        // 行列数上限：超限直接失败（在快照/落库之前拦截，不产生任何变更）
        if (parsed.headers().size() > ExcelService.MAX_TABLE_COLUMNS
            || parsed.rows().size() > ExcelService.MAX_TABLE_ROWS) {
            return OperationResult.failure(ExcelService.TABLE_LIMIT_MESSAGE);
        }
        // 直接使用传入的表格实例（skill 已 loadOrCreate），避免二次加载出新的实例
        // 导致调用方读取附件描述时拿到旧表头（0列×0行）
        ExcelTable loaded = table;
        // 覆盖前快照，保留原数据以便回滚
        if (OperationChecks.hasData(loaded)) {
            excelService.snapshotVersion(loaded, "覆盖生成表格");
        }
        // 首次创建（表格还没有数据）时应用指令里的标题（如「生成销售表」）；覆盖已有数据时保留原标题
        String title = operation.param("title");
        if (!OperationChecks.hasData(loaded)
            && title != null && !title.isBlank() && !"表格".equals(title)) {
            loaded.setTitle(title);
        }
        loaded.setHeaders(parsed.headers());
        loaded.setRows(parsed.rows());
        // 先导出再保存：导出失败（如公式错误）时不落库，避免留下无法导出的坏表格
        byte[] bytes = excelService.toXlsx(loaded);
        excelService.save(loaded);
        return OperationResult.success(
            "✅ 表格已生成（" + parsed.headers().size() + "列×"
                + parsed.rows().size() + "行）：" + loaded.getTitle(),
            bytes);
    }
}
