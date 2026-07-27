package com.clawbot.wechatbot.tools.currenttimetool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 获取精确到秒的当前时间工具，支持时区、时间戳、自定义格式。
 */
public class CurrentTimeTool implements FunctionTool {

    private static final String NAME = "get_current_time";
    private static final List<String> FORMAT_ENUM = Arrays.asList("default", "iso", "chinese", "timestamp_s", "timestamp_ms");

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

        ZonedDateTime now = ZonedDateTime.now(zoneId);

        try {
            if (!customPattern.isEmpty()) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(customPattern);
                return success(now.format(formatter), zoneId, now);
            }

            switch (format) {
                case "iso":
                    return success(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zoneId, now);
                case "chinese":
                    String chinese = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分ss秒"))
                        + "（" + zoneDisplayName(zoneId) + "，星期" + chineseWeekday(now.getDayOfWeek()) + "）";
                    return success(chinese, zoneId, now);
                case "timestamp_s":
                    return success(String.valueOf(now.toEpochSecond()), zoneId, now);
                case "timestamp_ms":
                    return success(String.valueOf(now.toInstant().toEpochMilli()), zoneId, now);
                default:
                    String def = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        + " " + zoneId.getId();
                    return success(def, zoneId, now);
            }
        } catch (Exception e) {
            return error("格式化时间失败：" + e.getMessage());
        }
    }

    private JsonNode wrapTool(ObjectNode function) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    private String success(String result, ZoneId zoneId, ZonedDateTime now) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "ok");
        node.put("timezone", zoneId.getId());
        node.put("zone_display", zoneDisplayName(zoneId));
        node.put("result", result);
        node.put("weekday", "星期" + chineseWeekday(now.getDayOfWeek()));
        node.put("day_of_year", "第" + now.getDayOfYear() + "天");
        node.put("timestamp_s", now.toEpochSecond());
        node.put("timestamp_ms", now.toInstant().toEpochMilli());
        return node.toString();
    }

    private String error(String msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "error");
        node.put("message", msg);
        return node.toString();
    }

    private static String chineseWeekday(DayOfWeek dayOfWeek) {
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        return names[dayOfWeek.getValue() - 1];
    }

    private static String zoneDisplayName(ZoneId zoneId) {
        Offset offset = Offset.fromZoneId(zoneId);
        if (offset.hours == 0 && offset.minutes == 0) return "UTC";
        String sign = offset.hours >= 0 ? "+" : "-";
        if (offset.minutes == 0) {
            return String.format("UTC%s%d", sign, Math.abs(offset.hours));
        }
        return String.format("UTC%s%d:%02d", sign, Math.abs(offset.hours), offset.minutes);
    }

    private static class Offset {
        final int hours;
        final int minutes;

        Offset(int hours, int minutes) {
            this.hours = hours;
            this.minutes = minutes;
        }

        static Offset fromZoneId(ZoneId zoneId) {
            int totalSeconds = ZonedDateTime.now(zoneId).getOffset().getTotalSeconds();
            int totalMinutes = totalSeconds / 60;
            return new Offset(totalMinutes / 60, Math.abs(totalMinutes % 60));
        }
    }
}