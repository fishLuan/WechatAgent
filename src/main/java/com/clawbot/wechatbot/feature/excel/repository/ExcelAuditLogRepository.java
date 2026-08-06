package com.clawbot.wechatbot.feature.excel.repository;

import com.clawbot.wechatbot.feature.excel.model.ExcelAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface ExcelAuditLogRepository
    extends MongoRepository<ExcelAuditLog, String> {

    /** 某用户全部审计日志，按创建时间倒序（最新在前）。 */
    List<ExcelAuditLog> findByWechatUserIdOrderByCreatedAtDesc(String wechatUserId);

    /** 某用户审计日志数量（超出保留上限时清理最旧的）。 */
    long countByWechatUserId(String wechatUserId);

    /** 删除某用户在指定时间之前写入的日志（保留上限清理用）。 */
    void deleteByWechatUserIdAndCreatedAtBefore(String wechatUserId, Instant createdAt);
}
