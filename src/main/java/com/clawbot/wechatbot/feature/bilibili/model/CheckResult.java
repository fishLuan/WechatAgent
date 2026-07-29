package com.clawbot.wechatbot.feature.bilibili.model;

import java.time.Instant;
import java.util.List;

/** 用户主动检查订阅更新时的汇总结果。 */
public record CheckResult(
    int checkedCount,
    int updateCount,
    List<EpisodeUpdateNotification> updates,
    Instant checkedAt
) {
    public CheckResult {
        if (checkedCount < 0 || updateCount < 0) {
            throw new IllegalArgumentException("检查数量和更新数量不能小于 0");
        }
        updates = updates == null ? List.of() : List.copyOf(updates);
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }
}
