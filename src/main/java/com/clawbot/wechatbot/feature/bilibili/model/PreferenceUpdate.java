package com.clawbot.wechatbot.feature.bilibili.model;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** 更新用户推荐偏好时使用的输入对象。 */
public record PreferenceUpdate(
    double minimumRating,
    int recommendationCount,
    LocalTime pushTime,
    Set<String> preferredGenres,
    boolean pushEnabled
) {
    public PreferenceUpdate {
        if (minimumRating < 0 || minimumRating > 10) {
            throw new IllegalArgumentException("minimumRating 必须在 0 到 10 之间");
        }
        if (recommendationCount < 1) {
            throw new IllegalArgumentException("recommendationCount 必须大于 0");
        }
        if (pushTime == null) {
            throw new IllegalArgumentException("pushTime 不能为空");
        }
        preferredGenres = preferredGenres == null
            ? Set.of()
            : Set.copyOf(new LinkedHashSet<>(preferredGenres));
    }
}
