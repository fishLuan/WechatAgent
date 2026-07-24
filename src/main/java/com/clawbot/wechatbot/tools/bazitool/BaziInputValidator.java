package com.clawbot.wechatbot.tools.bazitool;

import com.clawbot.wechatbot.tools.bazitool.model.BaziRequest;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and validates function-calling arguments without retaining personal data. */
final class BaziInputValidator {
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    private BaziInputValidator() {
    }

    static BaziRequest parse(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }

        String calendarText = arguments.path("calendar_type").asText("SOLAR")
            .trim().toUpperCase(Locale.ROOT);
        BaziRequest.CalendarType calendarType;
        try {
            calendarType = BaziRequest.CalendarType.valueOf(calendarText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("calendar_type 只能是 SOLAR 或 LUNAR");
        }
        int[] birthDate = parseDate(requiredText(arguments, "birth_date"), calendarType);
        LocalTime birthTime = parseTime(requiredText(arguments, "birth_time"));

        boolean leapMonth = arguments.path("leap_month").asBoolean(false);
        if (calendarType == BaziRequest.CalendarType.SOLAR && leapMonth) {
            throw new IllegalArgumentException("公历日期不能设置 leap_month=true");
        }
        if (birthDate[0] < 1900 || birthDate[0] > 2100) {
            throw new IllegalArgumentException("出生年份目前仅支持 1900 到 2100");
        }

        String timezone = arguments.path("timezone").asText("Asia/Shanghai").trim();
        if (!timezone.equalsIgnoreCase("Asia/Shanghai")
            && !timezone.equalsIgnoreCase("GMT+8")
            && !timezone.equalsIgnoreCase("UTC+8")
            && !timezone.equals("+08:00")) {
            throw new IllegalArgumentException(
                "第一版仅支持东八区出生时间，请使用 timezone=Asia/Shanghai");
        }

        int fortuneYear = arguments.path("fortune_year").asInt(Year.now().getValue());
        if (fortuneYear < 1900 || fortuneYear > 2100) {
            throw new IllegalArgumentException("fortune_year 目前仅支持 1900 到 2100");
        }
        return new BaziRequest(
            birthDate[0], birthDate[1], birthDate[2],
            birthTime, calendarType, leapMonth, fortuneYear);
    }

    private static String requiredText(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(name + " 参数不能为空");
        }
        return value.asText().trim();
    }

    private static int[] parseDate(String value, BaziRequest.CalendarType calendarType) {
        Matcher matcher = DATE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("birth_date 必须使用 yyyy-MM-dd 格式");
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        try {
            if (calendarType == BaziRequest.CalendarType.SOLAR) {
                LocalDate.of(year, month, day);
            } else if (month < 1 || month > 12 || day < 1 || day > 30) {
                throw new IllegalArgumentException("农历月份必须为 1 到 12，日期必须为 1 到 30");
            }
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("birth_date 不是有效的公历日期");
        }
        return new int[]{year, month, day};
    }

    private static LocalTime parseTime(String value) {
        Matcher matcher = TIME.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("birth_time 必须使用 HH:mm 或 HH:mm:ss 格式");
        }
        try {
            int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return LocalTime.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                second);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("birth_time 必须使用 HH:mm 或 HH:mm:ss 格式");
        }
    }
}
