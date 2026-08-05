package com.clawbot.wechatbot.service.agent.reference;

import java.time.Instant;

/** 一次可信字段从上游输出流向下游输入的审计记录，不保存字段明文。 */
public record DataLineageRecord(
    String sourceTaskId,
    String sourcePath,
    String targetTaskId,
    String targetPath,
    String valueHash,
    String valueType,
    int valueChars,
    Instant resolvedAt
) {
}
