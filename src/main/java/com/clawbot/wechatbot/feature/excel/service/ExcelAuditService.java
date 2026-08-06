package com.clawbot.wechatbot.feature.excel.service;

import com.clawbot.wechatbot.feature.excel.model.ExcelAuditLog;
import com.clawbot.wechatbot.feature.excel.repository.ExcelAuditLogRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/** 操作审计服务：每次表格操作后写一条日志，按用户保留最近 200 条（超出删除最旧的）。 */
@Component
public class ExcelAuditService {

    /** 每用户日志保留上限：超出后删除最旧的。 */
    private static final int MAX_KEPT_PER_USER = 200;
    /** detail 截断长度（字）：防止超长结果文案撑爆日志。 */
    private static final int MAX_DETAIL_LENGTH = 200;

    private final ExcelAuditLogRepository repository;

    public ExcelAuditService(ExcelAuditLogRepository repository) {
        this.repository = repository;
    }

    /** 写一条操作日志（detail 内部截断 200 字），并清理该用户超出上限的最旧记录。 */
    public void record(String wechatUserId, String workbookId, String operation,
                       boolean success, String detail) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            return;
        }
        ExcelAuditLog log = new ExcelAuditLog(
            wechatUserId, workbookId, operation, success, truncateDetail(detail));
        repository.save(log);
        // 保留上限：超出时删除最旧的（倒序列表的尾部），与版本快照清理口径一致
        long count = repository.countByWechatUserId(wechatUserId);
        if (count > MAX_KEPT_PER_USER) {
            List<ExcelAuditLog> all =
                repository.findByWechatUserIdOrderByCreatedAtDesc(wechatUserId);
            if (all.size() > MAX_KEPT_PER_USER) {
                repository.deleteAll(all.subList(MAX_KEPT_PER_USER, all.size()));
            }
        }
    }

    /** 某用户最近若干条日志（最新在前）。 */
    public List<ExcelAuditLog> list(String wechatUserId, int limit) {
        if (wechatUserId == null || wechatUserId.isBlank() || limit <= 0) {
            return List.of();
        }
        return repository.findByWechatUserIdOrderByCreatedAtDesc(wechatUserId)
            .stream().limit(limit).toList();
    }

    /** detail 截断 200 字：超长取前 200 字，避免结果文案撑爆日志。 */
    private static String truncateDetail(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= MAX_DETAIL_LENGTH
            ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
    }
}
