package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelAuditLog;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelAuditService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 查看操作日志：回复最近 10 条（时间、操作、成功/失败、摘要）；没有记录时给出提示。 */
public final class AuditListHandler implements ExcelOperationHandler {

    /** 日志回复条数上限。 */
    private static final int LIST_LIMIT = 10;
    /** 摘要截断长度（字）：日志里 detail 最多 200 字，回复时只展示开头。 */
    private static final int SUMMARY_LENGTH = 30;
    /** 日志回复中的时间格式。 */
    private static final DateTimeFormatter AUDIT_TIME_FORMAT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ExcelAuditService auditService;

    public AuditListHandler(ExcelAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.AUDIT_LIST;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable unused) {
        if (auditService == null) {
            return OperationResult.failure("操作日志服务不可用，请稍后重试。");
        }
        List<ExcelAuditLog> logs = auditService.list(userId, LIST_LIMIT);
        if (logs.isEmpty()) {
            return OperationResult.success(
                "📋 还没有操作记录。执行过表格操作后会在这里留下日志。");
        }
        StringBuilder reply = new StringBuilder("📋 最近 " + logs.size() + " 条操作记录：");
        for (ExcelAuditLog log : logs) {
            reply.append("\n· ").append(AUDIT_TIME_FORMAT.format(log.getCreatedAt()))
                .append(" ").append(displayOperation(log.getOperation()))
                .append(log.isSuccess() ? " ✅成功" : " ❌失败")
                .append(" ").append(summarize(log.getDetail()));
        }
        return OperationResult.success(reply.toString());
    }

    /** 操作类型名 → 中文说明（复合操作按 + 拆开逐个映射，无法识别的保留原文）。 */
    private static String displayOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return "未知操作";
        }
        return Arrays.stream(operation.split("\\+"))
            .map(name -> {
                try {
                    return ExcelOperationType.valueOf(name).label();
                } catch (IllegalArgumentException ignored) {
                    return name;
                }
            })
            .collect(Collectors.joining("+"));
    }

    /** 摘要：去掉行尾标点后截断，超长补省略号。 */
    private static String summarize(String detail) {
        String text = detail == null ? "" : detail.trim();
        if (text.length() <= SUMMARY_LENGTH) {
            return text;
        }
        return text.substring(0, SUMMARY_LENGTH) + "…";
    }
}
