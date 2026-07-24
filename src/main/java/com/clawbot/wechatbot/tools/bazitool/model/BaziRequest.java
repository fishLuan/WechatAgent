package com.clawbot.wechatbot.tools.bazitool.model;

import java.time.LocalTime;

/** Validated input for a BaZi calculation. */
public record BaziRequest(
    int birthYear,
    int birthMonth,
    int birthDay,
    LocalTime birthTime,
    CalendarType calendarType,
    boolean leapMonth,
    int fortuneYear
) {
    public enum CalendarType {
        SOLAR,
        LUNAR
    }
}
