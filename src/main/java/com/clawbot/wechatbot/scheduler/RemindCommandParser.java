package com.clawbot.wechatbot.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RemindCommandParser {

    private static final Pattern RELATIVE_PATTERN =
        Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HHMM_PATTERN =
        Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern DATETIME_PATTERN =
        Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2})$");
    private static final Pattern CANCEL_INDEX_PATTERN =
        Pattern.compile("/cancel\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_CANCEL_INDEX_PATTERN =
        Pattern.compile("第\\s*(\\d+)\\s*个");

    private static final Pattern NL_RELATIVE = Pattern.compile(
        "(\\d+)\\s*(秒|分|分钟|时|小时|天|日)\\s*(钟)?(后|以后|之后)?"
    );
    /** 明确写了「后/以后/之后」= 一定是 ONCE 相对时间，不是循环 */
    private static final Pattern NL_RELATIVE_EXPLICIT_ONCE = Pattern.compile(
        "(后|以后|之后)"
    );
    private static final Pattern NL_TIME_POINT = Pattern.compile(
        "(今天|今晚|明|明天|明早|早上|上午|中午|下午|晚上|今晚|凌晨)?\\s*(\\d{1,2})\\s*(点|点钟|:)\\s*(\\d{1,2})?(分|分钟)?"
    );
    private static final Pattern NL_EVERY = Pattern.compile(
        "^(每天|每日|每周)"
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ZoneId defaultTz;

    public RemindCommandParser(TaskSchedulerProperties props) {
        this.defaultTz = ZoneId.of(props.getDefaultTimezone());
    }

    public record ParsedResult(
        boolean success,
        ScheduledTask.TaskType type,
        Object scheduleParams,
        String message,
        String errorMessage
    ) {}

    public ParsedResult parse(String userId, String rawCommand) {
        String before = rawCommand == null ? "" : rawCommand.trim();
        String rest = normalizeRemindText(rawCommand);
        System.out.printf("[PARSER] 输入=\"%s\" → 标准格式=\"%s\"%n", before, rest);
        if (rest.isEmpty()) {
            return fail("提醒内容不能为空。\n" + helpText());
        }

        String[] parts = splitFirstTwoTokens(rest);
        String first = parts[0];
        String second = parts[1];
        String restMsg = parts[2];

        // ⚠️ 修复：「60s 喝水」这种 2 token 的命令，second 才是真正的 message，restMsg 是空
        //       之前只靠 restMsg 判断，把这种全判成了「缺少提醒内容」
        boolean firstLooksLikeTime = first != null && (
            RELATIVE_PATTERN.matcher(first).matches()
            || HHMM_PATTERN.matcher(first).matches()
            || DATETIME_PATTERN.matcher(first + " " + (second == null ? "" : second)).matches()
            || "cron".equalsIgnoreCase(first)
            || "every".equalsIgnoreCase(first)
        );
        if (restMsg.isEmpty() && second != null && firstLooksLikeTime) {
            // 把 second 合并进 message，不再当成「第二个时间参数」
            if ("cron".equalsIgnoreCase(first) || "every".equalsIgnoreCase(first)) {
                // cron 5段 / every 30m 至少需要 3 token 才是完整的，少了说明 second 才是内容 → 直接 fail 让 parseCron 自己报错
            } else {
                restMsg = second;
                second = null;
            }
        }

        if (restMsg.isEmpty()) {
            return fail("缺少提醒内容。\n" + helpText());
        }

        if ("cron".equalsIgnoreCase(first)) {
            return parseCron(userId, second, restMsg);
        }
        if ("every".equalsIgnoreCase(first)) {
            return parseEvery(userId, second, restMsg);
        }

        Matcher relMatcher = RELATIVE_PATTERN.matcher(first);
        if (relMatcher.matches()) {
            return parseRelative(userId, relMatcher, combine(first, second, restMsg, 1));
        }

        String maybeDt = (second != null && DATETIME_PATTERN.matcher(first + " " + second).matches())
            ? first + " " + second
            : null;
        if (maybeDt != null) {
            return parseDateTime(userId, maybeDt, cleanMessageVerbs(restMsg));
        }

        Matcher hmm = HHMM_PATTERN.matcher(first);
        if (hmm.matches()) {
            return parseHhmm(userId, hmm, cleanMessageVerbs(combine(first, second, restMsg, 0)));
        }

        return fail("时间格式看不懂：" + first + "\n" + helpText());
    }

    private String normalizeRemindText(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.toLowerCase().startsWith("/remind")) {
            s = s.substring("/remind".length()).trim();
        }
        // normalizeRemindText 入口先清第一遍（时间词在前的情况先把提醒我去掉）
        s = cleanMessageVerbs(s);

        boolean every = NL_EVERY.matcher(s).find()
            || s.contains("每天") || s.contains("每日")
            || s.contains("每周") || s.contains("每周一") || s.contains("每周二") || s.contains("每周三")
            || s.contains("每周四") || s.contains("每周五") || s.contains("每周六") || s.contains("每周日");
        boolean hasTomorrow = s.contains("明天") || s.contains("明早") || s.contains("后天");
        int addDays = hasTomorrow ? 1 : 0;

        Matcher m = NL_RELATIVE.matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            String u = switch (unit) {
                case "秒" -> "s";
                case "分", "分钟" -> "m";
                case "时", "小时" -> "h";
                case "天", "日" -> "d";
                default -> "m";
            };
            // ⚠️ 强规则：出现「后/以后/之后」= 一定是 ONCE，绝对不加 every
            boolean explicitOnce = NL_RELATIVE_EXPLICIT_ONCE.matcher(s).find();
            String token = (!explicitOnce && every) ? "every " : "";
            String restMsg = (m.start() == 0 ? s.substring(m.end()) : s.substring(0, m.start()) + s.substring(m.end()))
                .trim().replaceAll("^[，,。.!！?？\\s]+", "");
            restMsg = cleanMessageVerbs(restMsg);
            return token + n + u + " " + restMsg;
        }

        Matcher mp = NL_TIME_POINT.matcher(s);
        if (mp.find()) {
            String prefix = mp.group(1);
            int h = Integer.parseInt(mp.group(2));
            String mmStr = mp.group(4);
            int mm = mmStr == null ? 0 : Integer.parseInt(mmStr);
            boolean afternoon = prefix != null && (prefix.contains("下午") || prefix.contains("晚上") || prefix.contains("今晚"));
            if (afternoon && h < 12) h += 12;
            String prefixDay = (prefix != null && (prefix.contains("明") || prefix.contains("明天") || prefix.contains("明早"))) ? "明天 " : "";
            String timeStr = String.format("%02d:%02d", h, mm);
            String restMsg = (mp.start() == 0 ? s.substring(mp.end()) : s.substring(0, mp.start()) + s.substring(mp.end()))
                .trim().replaceAll("^[，,。.!！?？\\s]+", "");
            restMsg = cleanMessageVerbs(restMsg);
            return (every ? "every " : "") + prefixDay + timeStr + " " + restMsg;
        }

        return cleanMessageVerbs(s);
    }

    /** 把消息里残留的「提醒我/叫我喝水」的动作词去掉，只剩下真正的内容（比如喝水） */
    public static String cleanMessageVerbs(String s) {
        if (s == null) return "";
        String r = s.trim();
        if (r.isEmpty()) return "";
        // 1. 开头的标点符号
        r = r.replaceAll("^[，,。.!！?？、：:；;\\s「」\"'《》()（）\\-]+", "");
        // 2. 最常见的「请(帮我)?提醒我...」「请(帮我)?叫我...」一整坨直接拿掉
        r = r.replaceAll("^(请|麻烦|帮我|记得|到时|到时候|一定要)?\\s*(帮我|给我)?\\s*(提醒|叫|通知|发给|推送|告诉|提示|发)[我你他她它]?\\s*(一(条|下|次))?\\s*[:：,，、]?\\s*", "");
        // 3. 中后段再清一遍残留的「提醒我」「叫我」「发给我」
        r = r.replaceAll("(提醒|叫|通知|发给|发|推送|告诉|提示)[我你他她它]?\\s*(一(条|下|次))?\\s*[:：,，、]?\\s*", "");
        // 4. 「收到我一条消息...」「给我发一条...」
        r = r.replaceAll("^(给我|收到|我要)?\\s*(我要)?\\s*(收到|给我)\\s*(一(条|封|下|次))?\\s*(消息|微信|信息|提醒)?\\s*[:：,，、]?\\s*", "");
        r = r.replaceAll("(收到|给我发|发我)\\s*(一(条|封|下|次))?\\s*(消息|微信|信息)?\\s*[:：,，、]?\\s*", "");
        // 5. 最后的尾巴助词
        r = r.replaceAll("[，,。.!！?？、：:；;\\s「」\"'《》()（）\\-]+$", "");
        return r.trim();
    }

    public static Integer extractCancelIndex(String text) {
        if (text == null) return null;
        Matcher m1 = CANCEL_INDEX_PATTERN.matcher(text);
        if (m1.find()) return Integer.parseInt(m1.group(1));
        Matcher m2 = CHINESE_CANCEL_INDEX_PATTERN.matcher(text);
        if (m2.find()) return Integer.parseInt(m2.group(1));
        return null;
    }

    @SuppressWarnings("unchecked")
    public static ParsedResult fromJson(String jsonStr) {
        try {
            ObjectMapper om = new ObjectMapper();
            Map<String, Object> m = om.readValue(jsonStr, Map.class);
            String task = (String) m.get("task");
            Object time = m.get("time");
            String message = (String) m.get("message");
            if (task == null || time == null || message == null || message.isBlank()) {
                return fail("JSON 缺少字段 task/time/message：" + jsonStr);
            }
            ScheduledTask.TaskType type = switch (task.toLowerCase()) {
                case "once", "onetime", "一次性", "一次" -> ScheduledTask.TaskType.ONCE;
                case "cron", "周期", "循环", "every_day", "daily" -> ScheduledTask.TaskType.CRON;
                case "fixed_delay", "fixed", "interval", "间隔", "固定间隔" -> ScheduledTask.TaskType.FIXED_DELAY;
                default -> throw new IllegalArgumentException("未知 task 类型：" + task);
            };
            Object params = switch (type) {
                case ONCE -> parseInstantFromAny(time);
                case CRON -> {
                    String expr = String.valueOf(time);
                    CronExpression.parse(expr);
                    yield expr;
                }
                case FIXED_DELAY -> {
                    Duration d = parseDuration(String.valueOf(time));
                    if (d == null) throw new IllegalArgumentException("fixed_delay 时间格式不对：" + time);
                    if (d.toMinutes() < 1) throw new IllegalArgumentException("固定间隔不能小于 1 分钟");
                    yield d;
                }
            };
            return new ParsedResult(true, type, params, message, null);
        } catch (Exception e) {
            return fail("JSON 解析失败：" + e.getMessage());
        }
    }

    private static Instant parseInstantFromAny(Object time) {
        if (time == null) throw new IllegalArgumentException("time 为空");
        String s = String.valueOf(time).trim();
        try { return Instant.parse(s); } catch (Exception ignored) {}
        try { return ZonedDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(s, DATETIME_FORMAT).atZone(ZoneId.of("Asia/Shanghai")).toInstant(); } catch (Exception ignored) {}
        try {
            long ms = Long.parseLong(s);
            return Instant.ofEpochMilli(ms);
        } catch (Exception ignored) {}
        throw new IllegalArgumentException("看不懂的一次性时间：" + s + "（要 ISO8601，比如 2026-07-28T20:00:00+08:00）");
    }

    // ====================================
    // 内部解析方法
    // ====================================

    private ParsedResult parseCron(String userId, String expr, String message) {
        if (expr == null || expr.isBlank()) {
            return fail("cron 后面要跟表达式，比如：cron 0 0 22 ? * FRI\n" + helpText());
        }
        try {
            CronExpression.parse(expr);
        } catch (Exception e) {
            return fail("Cron 表达式不合法：" + expr + "，原因：" + e.getMessage());
        }
        return new ParsedResult(true, ScheduledTask.TaskType.CRON, expr, message, null);
    }

    private ParsedResult parseEvery(String userId, String token, String message) {
        if (token == null) {
            return fail("every 后面要跟时间，比如：every 30m\n" + helpText());
        }
        Duration d = parseDuration(token);
        if (d == null) {
            return fail("every 后面的时间格式不对：" + token + "（示例：30m / 2h / 1d）");
        }
        if (d.toMinutes() < 1) {
            return fail("every 间隔不能小于 1 分钟，避免刷屏");
        }
        return new ParsedResult(true, ScheduledTask.TaskType.FIXED_DELAY, d, message, null);
    }

    private ParsedResult parseRelative(String userId, Matcher m, String message) {
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2).toLowerCase();
        Duration d = switch (unit) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> null;
        };
        if (d == null) return fail("不支持的时间单位：" + unit);
        Instant at = Instant.now().plus(d);
        return new ParsedResult(true, ScheduledTask.TaskType.ONCE, at, message, null);
    }

    private ParsedResult parseDateTime(String userId, String dt, String message) {
        try {
            LocalDateTime ldt = LocalDateTime.parse(dt, DATETIME_FORMAT);
            Instant at = ldt.atZone(defaultTz).toInstant();
            if (at.isBefore(Instant.now())) {
                return fail("指定的时间 " + dt + " 已经过去了，请指定一个未来的时间");
            }
            return new ParsedResult(true, ScheduledTask.TaskType.ONCE, at, message, null);
        } catch (Exception e) {
            return fail("日期时间格式不对：" + dt + "（示例：2026-07-29 15:00）");
        }
    }

    private ParsedResult parseHhmm(String userId, Matcher m, String message) {
        int h = Integer.parseInt(m.group(1));
        int mm = Integer.parseInt(m.group(2));
        if (h < 0 || h > 23 || mm < 0 || mm > 59) {
            return fail("时间不合法：" + m.group(0));
        }
        var today = LocalDateTime.now(defaultTz)
            .withHour(h).withMinute(mm).withSecond(0).withNano(0);
        Instant at = today.toInstant(defaultTz.getRules().getOffset(today));
        if (at.isBefore(Instant.now())) {
            at = at.plus(Duration.ofDays(1));
        }
        return new ParsedResult(true, ScheduledTask.TaskType.ONCE, at, message, null);
    }

    // ====================================
    // 辅助
    // ====================================

    private static ParsedResult fail(String msg) {
        return new ParsedResult(false, null, null, null, msg);
    }

    private static Duration parseDuration(String token) {
        Matcher m = RELATIVE_PATTERN.matcher(token);
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(1));
        return switch (m.group(2).toLowerCase()) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> null;
        };
    }

    private static String combine(String first, String second, String restMsg, int skipTokens) {
        if (skipTokens <= 0 && second == null) return restMsg;
        if (skipTokens <= 0) return second + " " + restMsg;
        return restMsg;
    }

    private static String[] splitFirstTwoTokens(String input) {
        String[] arr = input.trim().split("\\s+", 3);
        String first = arr.length > 0 ? arr[0] : null;
        String second = arr.length > 1 ? arr[1] : null;
        String rest = arr.length > 2 ? arr[2] : "";
        return new String[]{first, second, rest};
    }

    public static String helpText() {
        return """
            直接说人话或者用命令都可以（两种写法都行）：
              • 自然语言示例：
                「1分钟后提醒我喝水」
                「明天下午3点叫我开会」
                「每天早上8点提醒我起床」
                「22点叫我关电脑」
              • 命令格式（任选一种时间）：
                · 30m / 2h / 1d / 90s      → 30分钟后 / 2小时后 / 1天后 / 90秒后
                · 22:00                      → 今晚 22 点（已过则明晚）
                · 2026-07-29 15:00           → 指定年月日时分
                · cron 0 0 10 ? * MON        → 每周一上午 10 点
                · every 30m                  → 每 30 分钟循环提醒
            其他命令：
              · /tasks          查看所有任务（带序号，5 分钟内可取消）
              · /cancel 1       取消第 1 个任务（或说「取消第1个」）
              · /cancel-all     取消所有任务（或说「取消所有提醒」）""";
    }
}