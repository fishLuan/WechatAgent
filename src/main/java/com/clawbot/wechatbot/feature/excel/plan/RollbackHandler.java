package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/**
 * 回滚操作：先对当前状态快照（「回滚操作」，回滚可撤销），再恢复最新版本。
 * 业务逻辑与重构前 rollback 一致。
 */
public final class RollbackHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public RollbackHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.ROLLBACK;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        if (excelService.versionCount(table) == 0) {
            return OperationResult.failure(
                "❌ 没有可回滚的版本。做过「添加/修改/删除/覆盖/导入」操作后"
                    + "会生成版本记录，可回滚到最近一次操作前。");
        }
        // 回滚前先对当前状态快照（「回滚操作」），保留回滚前的数据，便于再次撤销
        excelService.snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        boolean restored = excelService.restoreLatestVersion(table);
        if (!restored) {
            return OperationResult.failure("❌ 没有可回滚的版本。");
        }
        excelService.save(table);
        return OperationResult.success("✅ 已回滚到上一版本。", excelService.toXlsx(table));
    }
}
