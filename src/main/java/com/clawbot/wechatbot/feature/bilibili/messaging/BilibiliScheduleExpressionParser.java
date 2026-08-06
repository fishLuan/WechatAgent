package com.clawbot.wechatbot.feature.bilibili.messaging;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析自然语言中的单次、每日和每周时间表达式。 */
final class BilibiliScheduleExpressionParser {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern WEEKDAY = Pattern.compile("(?:周|星期)([一二三四五六日天])");
    private static final Pattern ARABIC_TIME = Pattern.compile(
        "(?:每天|每日)?.*?((?:[01]?\\d|2[0-3])[:：][0-5]\\d)");
    private static final Pattern CHINESE_TIME = Pattern.compile(
        "(凌晨|早上|上午|中午|下午|晚上)?\\s*"
            + "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})[点时]"
            + "(?:(半)|([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})分?)?");
    private static final Pattern PERIOD_COLON_TIME = Pattern.compile(
        "(凌晨|早上|上午|中午|下午|晚上)\\s*([0-9]{1,2})[:：]([0-5][0-9])");
    private static final Pattern QUARTER_TIME = Pattern.compile(
        "(凌晨|早上|上午|中午|下午|晚上)?\\s*"
            + "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})[点时](一刻|三刻|整)");
    private static final Pattern RELATIVE_TIME = Pattern.compile(
        "([0-9]{1,4}|[零〇一二两三四五六七八九十百]{1,5})(小时|分钟)(?:之后|后)");

    private BilibiliScheduleExpressionParser() {}

    static ScheduleValue parse(String text) {
        Matcher relative = RELATIVE_TIME.matcher(text);
        if (relative.find()) {
            int amount = chineseNumber(relative.group(1));
            if (amount < 1) return null;
            LocalDateTime fireAt = LocalDateTime.now(ZONE);
            fireAt = "小时".equals(relative.group(2))
                ? fireAt.plusHours(amount) : fireAt.plusMinutes(amount);
            return new ScheduleValue(
                "ONCE", String.valueOf(fireAt.atZone(ZONE).toInstant().toEpochMilli()), null);
        }
        LocalTime time = extractTime(text);
        if (time == null) return null;
        if (text.contains("明天") || text.contains("后天")) {
            int days = text.contains("后天") ? 2 : 1;
            LocalDateTime fireAt = LocalDate.now(ZONE).plusDays(days).atTime(time);
            return new ScheduleValue(
                "ONCE", String.valueOf(fireAt.atZone(ZONE).toInstant().toEpochMilli()), null);
        }
        if (text.contains("每周") || text.contains("每星期") || text.contains("每个星期")) {
            Set<String> days = new LinkedHashSet<>();
            Matcher weekday = WEEKDAY.matcher(text);
            while (weekday.find()) days.add(dayName(weekday.group(1)));
            if (days.isEmpty()) return null;
            return new ScheduleValue("WEEKLY", time.toString(), String.join(",", days));
        }
        return new ScheduleValue("DAILY", time.toString(), null);
    }

    static boolean looksLikeTimedPush(String text, boolean hasContentType) {
        if (!hasPushAction(text) || !hasContentType) return false;
        return text.matches(".*(?:[0-9]{1,2}\\s*[点时]|[0-9]{1,2}[:：][0-9]{1,2}).*")
            || CHINESE_TIME.matcher(text).find() || RELATIVE_TIME.matcher(text).find()
            || text.contains("明天") || text.contains("后天")
            || text.contains("每周") || text.contains("每星期");
    }

    static boolean hasPushAction(String text) {
        return text != null && (text.contains("推送") || text.contains("推荐")
            || text.matches(".*(?:给我|帮我|请)?推(?:一下)?(?:动漫|番剧|电影|剧集|电视剧).*") );
    }

    private static LocalTime extractTime(String text) {
        Matcher matcher = PERIOD_COLON_TIME.matcher(text);
        if (matcher.find()) {
            int hour = adjustHour(matcher.group(1), Integer.parseInt(matcher.group(2)));
            return hour > 23 ? null : LocalTime.of(hour, Integer.parseInt(matcher.group(3)));
        }
        matcher = QUARTER_TIME.matcher(text);
        if (matcher.find()) {
            int hour = adjustHour(matcher.group(1), chineseNumber(matcher.group(2)));
            int minute = switch (matcher.group(3)) { case "一刻" -> 15; case "三刻" -> 45; default -> 0; };
            return hour > 23 ? null : LocalTime.of(hour, minute);
        }
        matcher = ARABIC_TIME.matcher(text);
        if (matcher.find()) return LocalTime.parse(matcher.group(1).replace('：', ':'));
        matcher = CHINESE_TIME.matcher(text);
        if (!matcher.find()) return null;
        int hour = adjustHour(matcher.group(1), chineseNumber(matcher.group(2)));
        int minute = matcher.group(3) != null ? 30
            : matcher.group(4) == null ? 0 : chineseNumber(matcher.group(4));
        return hour > 23 || minute > 59 ? null : LocalTime.of(hour, minute);
    }

    private static int adjustHour(String period, int hour) {
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        if ("凌晨".equals(period) && hour == 12) hour = 0;
        return hour;
    }

    private static int chineseNumber(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        String normalized = value.replace('两', '二').replace('〇', '零');
        if (normalized.equals("十")) return 10;
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(normalized.charAt(ten - 1));
            int ones = ten == normalized.length() - 1 ? 0 : digit(normalized.charAt(ten + 1));
            return tens * 10 + ones;
        }
        int result = 0;
        for (int i = 0; i < normalized.length(); i++) result = result * 10 + digit(normalized.charAt(i));
        return result;
    }

    private static int digit(char value) { return "零一二三四五六七八九".indexOf(value); }
    private static String dayName(String value) {
        return switch (value) {
            case "一" -> "MONDAY"; case "二" -> "TUESDAY"; case "三" -> "WEDNESDAY";
            case "四" -> "THURSDAY"; case "五" -> "FRIDAY"; case "六" -> "SATURDAY";
            default -> "SUNDAY";
        };
    }

    record ScheduleValue(String kind, String value, String weekdays) {}
}
