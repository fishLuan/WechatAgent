package com.clawbot.wechatbot.tools.currenttimetool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneRules;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 获取精确到秒的当前时间工具，支持时区、时间戳、自定义格式。
 */
public class CurrentTimeTool implements FunctionTool {

    private static final String NAME = "get_current_time";
    private static final List<String> FORMAT_ENUM = Arrays.asList("default", "iso", "chinese", "timestamp_s", "timestamp_ms");

    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CHINESE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分ss秒");
    private static final String[] WEEKDAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    private static final ConcurrentMap<String, DateTimeFormatter> CUSTOM_FORMATTER_CACHE = new ConcurrentHashMap<>();
    private static final int CUSTOM_FORMATTER_CACHE_MAX = 64;

    private final ObjectMapper mapper;

    public CurrentTimeTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description", "获取精确到秒的当前时间。支持指定时区、返回标准格式时间或 Unix 时间戳。"
            + "当用户询问\"现在几点\"、\"当前时间\"、\"时间戳\"、或需要特定时区的时间时必须调用此工具，禁止编造时间。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        properties.putObject("timezone")
            .put("type", "string")
            .put("description", "时区 ID，例如 Asia/Shanghai（东8区）、UTC、America/New_York、Europe/London。默认 Asia/Shanghai");

        ObjectNode formatProperty = properties.putObject("format");
        formatProperty.put("type", "string");
        formatProperty.set("enum", mapper.valueToTree(FORMAT_ENUM));
        formatProperty.put("description",
            "输出格式：default(默认，如 2026-07-24 15:30:45)、iso(ISO-8601)、chinese(中文友好，如 2026年07月24日 15时30分45秒)、"
            + "timestamp_s(秒级Unix时间戳)、timestamp_ms(毫秒级Unix时间戳)。默认 default");

        properties.putObject("custom_pattern")
            .put("type", "string")
            .put("description", "自定义 DateTimeFormatter 格式，例如 yyyy/MM/dd HH:mm:ss。若指定则覆盖 format 参数");

        return wrapTool(function);
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String timezone = arguments == null ? "Asia/Shanghai" : arguments.path("timezone").asText("Asia/Shanghai").trim();
        String format = arguments == null ? "default" : arguments.path("format").asText("default").trim();
        String customPattern = arguments == null ? "" : arguments.path("custom_pattern").asText("").trim();

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone.isEmpty() ? "Asia/Shanghai" : timezone);
        } catch (Exception e) {
            return error("无效的时区 ID：" + timezone + "，请使用标准时区名，例如 Asia/Shanghai、UTC、America/New_York");
        }

        Instant nowInstant = Instant.now();
        ZonedDateTime now = ZonedDateTime.ofInstant(nowInstant, zoneId);
        long epochSecond = nowInstant.getEpochSecond();
        long epochMilli = nowInstant.toEpochMilli();
        ZoneOffset offset = zoneId.getRules().getOffset(nowInstant);

        try {
            if (!customPattern.isEmpty()) {
                DateTimeFormatter formatter = getCustomFormatter(customPattern);
                return success(now.format(formatter), zoneId, now, offset, epochSecond, epochMilli);
            }

            switch (format) {
                case "iso":
                    return success(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zoneId, now, offset, epochSecond, epochMilli);
                case "chinese":
                    String chinese = now.format(CHINESE_FORMATTER)
                        + "（" + zoneDisplayName(offset) + "，星期" + chineseWeekday(now.getDayOfWeek()) + "）";
                    return success(chinese, zoneId, now, offset, epochSecond, epochMilli);
                case "timestamp_s":
                    return success(String.valueOf(epochSecond), zoneId, now, offset, epochSecond, epochMilli);
                case "timestamp_ms":
                    return success(String.valueOf(epochMilli), zoneId, now, offset, epochSecond, epochMilli);
                default:
                    String def = now.format(DEFAULT_FORMATTER) + " " + zoneId.getId();
                    return success(def, zoneId, now, offset, epochSecond, epochMilli);
            }
        } catch (Exception e) {
            return error("格式化时间失败：" + e.getMessage());
        }
    }

    private static DateTimeFormatter getCustomFormatter(String pattern) {
        DateTimeFormatter formatter = CUSTOM_FORMATTER_CACHE.get(pattern);
        if (formatter != null) return formatter;
        if (CUSTOM_FORMATTER_CACHE.size() >= CUSTOM_FORMATTER_CACHE_MAX) {
            CUSTOM_FORMATTER_CACHE.clear();
        }
        formatter = DateTimeFormatter.ofPattern(pattern);
        CUSTOM_FORMATTER_CACHE.put(pattern, formatter);
        return formatter;
    }

    private JsonNode wrapTool(ObjectNode function) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    private String success(String result, ZoneId zoneId, ZonedDateTime now, ZoneOffset offset,
                           long epochSecond, long epochMilli) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "ok");
        node.put("timezone", zoneId.getId());
        node.put("zone_display", zoneDisplayName(offset));
        node.put("result", result);
        node.put("weekday", "星期" + chineseWeekday(now.getDayOfWeek()));
        node.put("day_of_year", "第" + now.getDayOfYear() + "天");
        node.put("timestamp_s", epochSecond);
        node.put("timestamp_ms", epochMilli);
        return node.toString();
    }

    private String error(String msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "error");
        node.put("message", msg);
        return node.toString();
    }

    private static String chineseWeekday(DayOfWeek dayOfWeek) {
        return WEEKDAY_NAMES[dayOfWeek.getValue() - 1];
    }

    private static String zoneDisplayName(ZoneOffset offset) {
        int totalSeconds = offset.getTotalSeconds();
        if (totalSeconds == 0) return "UTC";
        int totalMinutes = totalSeconds / 60;
        int hours = totalMinutes / 60;
        int minutes = Math.abs(totalMinutes % 60);
        String sign = hours >= 0 ? "+" : "-";
        if (minutes == 0) {
            return String.format("UTC%s%d", sign, Math.abs(hours));
        }
        return String.format("UTC%s%d:%02d", sign, Math.abs(hours), minutes);
    }
}