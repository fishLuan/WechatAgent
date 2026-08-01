package com.clawbot.wechatbot.feature.bilibili.messaging;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 用户查询作品更新时使用的时间范围。 */
public enum BilibiliUpdateRange {
    TODAY("今天"),
    LAST_24_HOURS("最近24小时"),
    LAST_3_DAYS("最近3天"),
    LAST_7_DAYS("最近7天"),
    THIS_WEEK("本周");

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private final String displayName;

    BilibiliUpdateRange(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public Instant from(Instant now) {
        ZonedDateTime current = now.atZone(BEIJING);
        return switch (this) {
            case TODAY -> current.toLocalDate().atStartOfDay(BEIJING).toInstant();
            case LAST_24_HOURS -> now.minusSeconds(24 * 60 * 60L);
            case LAST_3_DAYS -> now.minusSeconds(3 * 24 * 60 * 60L);
            case LAST_7_DAYS -> now.minusSeconds(7 * 24 * 60 * 60L);
            case THIS_WEEK -> {
                LocalDate monday = current.toLocalDate()
                    .minusDays(current.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
                yield monday.atStartOfDay(BEIJING).toInstant();
            }
        };
    }

    public static BilibiliUpdateRange fromCommandValue(String value) {
        if (value == null || value.isBlank()) return TODAY;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return TODAY;
        }
    }
}
