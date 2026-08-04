package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 版本对比：取该表最新版本快照与当前表对比，回复中文差异摘要（无版本时给出提示）。 */
public final class VersionDiffHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public VersionDiffHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.VERSION_DIFF;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        return OperationResult.success(excelService.diffVersions(table));
    }
}
