package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 查看版本历史：回复版本数量与最近几次操作说明/时间（业务逻辑与重构前 versionHistory 一致）。 */
public final class VersionHistoryHandler implements ExcelOperationHandler {

    /** 版本历史回复中的时间格式。 */
    private static final DateTimeFormatter VERSION_TIME_FORMAT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ExcelService excelService;

    public VersionHistoryHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.VERSION_HISTORY;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        long count = excelService.versionCount(table);
        if (count == 0) {
            return OperationResult.success(
                "📜 还没有版本记录。做过「添加/修改/删除/覆盖/导入」操作后即可回滚。");
        }
        List<ExcelTableVersion> recent = excelService.recentVersions(table, 5);
        StringBuilder reply = new StringBuilder(
            "📜 共 " + count + " 条版本记录（每表最多保留 20 条）：");
        for (ExcelTableVersion version : recent) {
            String description = version.getDescription() == null
                ? "未知操作" : version.getDescription();
            reply.append("\n· ").append(description)
                .append("（").append(VERSION_TIME_FORMAT.format(version.getCreatedAt())).append("）");
        }
        if (count > recent.size()) {
            reply.append("\n……共 ").append(count)
                .append(" 条，仅展示最近 ").append(recent.size()).append(" 条");
        }
        return OperationResult.success(reply.toString());
    }
}
