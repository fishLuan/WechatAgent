package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.time.LocalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无副作用的B站命令解析器。
 *
 * <p>配置类命令优先于推荐命令，避免“每天十点推送电影”被误判成立即推荐。</p>
 */
public final class BilibiliCommandParser {
    private BilibiliCommandParser() {
    }

    public enum CmdType {
        TODAY_RECOMMEND_ANIME,
        TODAY_RECOMMEND_MOVIE,
        TODAY_RECOMMEND_SERIES,
        CONFIGURE_DAILY_RECOMMENDATION,
        TODAY_UPDATES_ANIME,
        TODAY_UPDATES_SERIES,
        SUBSCRIBE_BY_INDEX,
        SUBSCRIBE_BY_URL,
        SEARCH_BY_TITLE,
        SUBSCRIBE_BY_TITLE,
        MARK_WANT_TO_WATCH,
        MARK_WATCHED,
        MARK_DISLIKED,
        MARK_TITLE,
        LIST_SUBSCRIPTIONS,
        CANCEL_SUBSCRIPTION,
        PAUSE_SUBSCRIPTION,
        RESUME_SUBSCRIPTION,
        SET_PUSH_TIME,
        SET_MIN_RATING,
        SET_RECOMMEND_COUNT,
        SET_WEEKDAY_PUSH_POLICY,
        TOGGLE_PUSH,
        SHOW_PREFERENCES,
        CHECK_UPDATES_NOW,
        RAG_QA,
        RAG_SIMILAR,
        UNKNOWN
    }

    public record ParsedCommand(
        CmdType type,
        Integer index,
        String subscriptionId,
        ContentType contentType,
        String url,
        String fieldName,
        String fieldValue,
        Boolean pushEnabled,
        String title,
        String state,
        Double minimumRating,
        Integer recommendationCount
    ) {
        static ParsedCommand of(CmdType type) {
            return new ParsedCommand(
                type, null, null, null, null, null, null, null,
                null, null, null, null);
        }

        static ParsedCommand unknown() {
            return of(CmdType.UNKNOWN);
        }
    }

    private static final Pattern BILIBILI_URL = Pattern.compile(
        "(?i)(https?://)?(?:(?:www|m)\\.)?(?:bilibili\\.com|b23\\.tv)/[^\\s，。！？]+");
    private static final Pattern INDEX_ACTION = Pattern.compile(
        "^(订阅|追更|想看|看过|不喜欢)\\s*(?:第\\s*)?"
            + "(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(?:个|部)?\\s*$");
    private static final Pattern MANAGE_SUBSCRIPTION = Pattern.compile(
        "^(取消|删除|移除|暂停|恢复|继续)\\s*订阅\\s*(\\d{1,2}|[0-9a-fA-F]{20,})\\s*$");
    private static final Pattern EXACT_TIME = Pattern.compile(
        "^设置\\s*(动漫|番剧|电影|剧集|电视剧)\\s*推送时间\\s*([0-2]?\\d[:：][0-5]\\d)\\s*$");
    private static final Pattern EXACT_RATING = Pattern.compile(
        "^设置\\s*(动漫|番剧|电影|剧集|电视剧)\\s*最低评分\\s*(10(?:\\.0)?|\\d(?:\\.\\d)?)\\s*$");
    private static final Pattern EXACT_COUNT = Pattern.compile(
        "^设置\\s*(动漫|番剧|电影|剧集|电视剧)\\s*推荐数量\\s*(\\d{1,2})\\s*$");
    private static final Pattern TOGGLE = Pattern.compile(
        "^(开启|打开|启用|关闭|禁用|停止)\\s*(动漫|番剧|电影|剧集|电视剧)\\s*(?:推送|每日推荐)?\\s*$");
    private static final Pattern TITLE_SEARCH = Pattern.compile(
        "https?://(?:www\\.|m\\.)?bilibili\\.com/[a-zA-Z0-9/?=&%_.~#-]+" +
        "|https?://b23\\.tv/[a-zA-Z0-9]+");

    private static final Pattern SHOW_PREF = Pattern.compile(
        "^(查看|我的|显示|列出)\\s*(偏好|设置|推荐设置)\\s*$");

    private static final Pattern CHECK_NOW = Pattern.compile(
        "^(立即|马上|现在)?\\s*(检查更新|刷新更新|扫一下更新)\\s*$");

    private static final Pattern TODAY_UPDATES = Pattern.compile(
        "^(?:今天|今日|现在).*?(?:更新|上新|出了|上线|新番|新动漫).*?(动漫|番剧|番(?!茄|号)|剧集|电视剧|剧)(?:呢|吗|啊|呀)?\\s*$");

    private static final Pattern SEARCH_BY_TITLE = Pattern.compile(
        "^(?:搜索|查找|搜一下|找一下|帮我找(?:一下)?)\\s*(?:B站)?\\s*(.+?)\\s*$");
    private static final Pattern TITLE_SUBSCRIBE = Pattern.compile(
        "^(?:(?:我想|我要|请|帮我)\\s*)?(?:订阅|追更)\\s*(?:一下|下)?\\s*(?:作品)?\\s*(.+?)\\s*$");
    private static final Pattern TITLE_STATE = Pattern.compile(
        "^(?:我)?(?:已经|刚刚|刚)?\\s*(看过|看完了|想看|不喜欢)\\s*(?:了)?\\s*(.+?)\\s*$");
    private static final Pattern RAG_SIMILAR = Pattern.compile(
        "^(?:推荐|找|来点|有没有)\\s*(?:几部|一些|类似|像)?\\s*(?:《(.+?)》|(.+?))\\s*(?:类似|相似|同类型|同题材)\\s*(?:的)?\\s*(动漫|番剧|电影|剧集|电视剧|番)?\\s*$");
    private static final Pattern RAG_SIMILAR_PREFIX = Pattern.compile(
        "^(?:推荐|找|来点|有没有)\\s*(?:几部|一些)?\\s*(?:类似|像)\\s*(?:《(.+?)》|(.+?))\\s*(?:的)?\\s*(动漫|番剧|电影|剧集|电视剧|番)?\\s*$");
    private static final Pattern RAG_INTENT = Pattern.compile(
        ".*(智能推荐|为什么推荐|为啥推荐|类似|相似|适合我|按我的偏好|我适合|有没有好看的|最近看什么|订阅.*更新).*");
    private static final Pattern WEEKDAY = Pattern.compile(
        "(?:周|星期)([一二三四五六日天])");
    private static final Pattern ARABIC_TIME = Pattern.compile(
        "(?:每天|每日)?.*?((?:[01]?\\d|2[0-3])[:：][0-5]\\d)");
    private static final Pattern RATING = Pattern.compile(
        "(10(?:\\.0)?|\\d(?:\\.\\d)?)\\s*分(?:以上|起)?");
    private static final Pattern COUNT = Pattern.compile("(\\d{1,2})\\s*(?:部|个)");
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
        "([0-9]{1,4}|[零〇一二两三四五六七八九十百]{1,5})(小时|分钟)后");

    public static ParsedCommand parse(String input) {
        if (input == null || input.isBlank()) return ParsedCommand.unknown();
        String text = input.trim();

        Matcher url = BILIBILI_URL.matcher(text);
        if (url.find()) {
            String normalized = url.group();
            if (!normalized.startsWith("http")) normalized = "https://" + normalized;
            return new ParsedCommand(
                CmdType.SUBSCRIBE_BY_URL, null, null, null, normalized,
                null, null, null, null, null, null, null);
        }

        ParsedCommand daily = parseDailyRecommendation(text);
        if (daily != null) return daily;

        ParsedCommand weekdayPolicy = parseWeekdayPushPolicy(text);
        if (weekdayPolicy != null) return weekdayPolicy;

        Matcher matcher = EXACT_TIME.matcher(text);
        if (matcher.matches()) {
            return preferenceCommand(
                CmdType.SET_PUSH_TIME, typeOf(matcher.group(1)),
                "push_time", normalizeTime(matcher.group(2)));
        }
        matcher = EXACT_RATING.matcher(text);
        if (matcher.matches()) {
            return preferenceCommand(
                CmdType.SET_MIN_RATING, typeOf(matcher.group(1)),
                "min_rating", matcher.group(2));
        }
        matcher = EXACT_COUNT.matcher(text);
        if (matcher.matches()) {
            return preferenceCommand(
                CmdType.SET_RECOMMEND_COUNT, typeOf(matcher.group(1)),
                "count", matcher.group(2));
        }
        matcher = TOGGLE.matcher(text);
        if (matcher.matches()) {
            boolean enabled = !matcher.group(1).matches("关闭|禁用|停止");
            return new ParsedCommand(
                CmdType.TOGGLE_PUSH, null, null, typeOf(matcher.group(2)),
                null, null, null, enabled, null, null, null, null);
        }

        matcher = INDEX_ACTION.matcher(text);
        if (matcher.matches()) {
            CmdType type = switch (matcher.group(1)) {
                case "订阅", "追更" -> CmdType.SUBSCRIBE_BY_INDEX;
                case "想看" -> CmdType.MARK_WANT_TO_WATCH;
                case "看过" -> CmdType.MARK_WATCHED;
                default -> CmdType.MARK_DISLIKED;
            };
            return new ParsedCommand(
                type, parseIndex(matcher.group(2)), null, null,
                null, null, null, null, null, null, null, null);
        }

        matcher = MANAGE_SUBSCRIPTION.matcher(text);
        if (matcher.matches()) {
            CmdType type = switch (matcher.group(1)) {
                case "取消", "删除", "移除" -> CmdType.CANCEL_SUBSCRIPTION;
                case "暂停" -> CmdType.PAUSE_SUBSCRIPTION;
                default -> CmdType.RESUME_SUBSCRIPTION;
            };
            String target = matcher.group(2);
            return new ParsedCommand(
                type,
                target.matches("\\d{1,2}") ? Integer.parseInt(target) : null,
                target.matches("\\d{1,2}") ? null : target,
                null, null, null, null, null, null, null, null, null);
        }

        if (text.matches("^(我的|查看|列出)?\\s*(订阅|订阅列表|追更列表)$")) {
            return ParsedCommand.of(CmdType.LIST_SUBSCRIPTIONS);
        }
        if (text.matches("^(查看|我的|显示|列出)\\s*(偏好|设置|推荐设置)$")) {
            return ParsedCommand.of(CmdType.SHOW_PREFERENCES);
        }
        if (text.matches("^(立即|马上|现在)?\\s*(检查更新|刷新更新|扫一下更新)$")) {
            return ParsedCommand.of(CmdType.CHECK_UPDATES_NOW);
        }

        ParsedCommand rag = parseRag(text);
        if (rag != null) return rag;
        // 11. 今日更新（"今天更新了哪些动漫"/"今日更新的番"/"今天有什么新番"）
        matcher = TODAY_UPDATES.matcher(text);
        if (matcher.find()) {
            ContentType ct = typeOf(matcher.group(1));
            CmdType ty = ct == ContentType.SERIES ? CmdType.TODAY_UPDATES_SERIES : CmdType.TODAY_UPDATES_ANIME;
            return new ParsedCommand(ty, null, null, ct, null, null, null, null, null, null, null, null);
        }

        matcher = SEARCH_BY_TITLE.matcher(text);
        if (matcher.find() && !matcher.group(1).isBlank()) {
            return new ParsedCommand(CmdType.SEARCH_BY_TITLE,
                null, null, null, null, null, null, null,
                matcher.group(1).trim(), null, null, null);
        }

        matcher = TITLE_STATE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(
                CmdType.MARK_TITLE, null, null, null, null, null, null, null,
                cleanTitle(matcher.group(2)), normalizeState(matcher.group(1)),
                null, null);
        }
        matcher = TITLE_SEARCH.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(
                CmdType.SEARCH_BY_TITLE, null, null, null, null, null, null,
                null, cleanTitle(matcher.group(1)), null, null, null);
        }
        matcher = TITLE_SUBSCRIBE.matcher(text);
        if (matcher.matches() && !matcher.group(1).matches("\\d+")) {
            return new ParsedCommand(
                CmdType.SUBSCRIBE_BY_TITLE, null, null, null, null, null, null,
                null, cleanTitle(matcher.group(1)), null, null, null);
        }

        // 含明确时间形态的推送指令只能作为配置处理。即使时间不合法，
        // 也不能继续降级为“立即推荐”，否则“25点推送电影”会马上发送。
        if (looksLikeTimedPush(text)) {
            return ParsedCommand.unknown();
        }

        ContentType contentType = inferType(text);
        if (contentType != null && containsRecommendationIntent(text)) {
            CmdType type = switch (contentType) {
                case BANGUMI -> CmdType.TODAY_RECOMMEND_ANIME;
                case SERIES -> CmdType.TODAY_RECOMMEND_SERIES;
                case MOVIE -> CmdType.TODAY_RECOMMEND_MOVIE;
                case UPLOADER -> CmdType.UNKNOWN;
            };
            return new ParsedCommand(
                type, null, null, contentType, null, null, null, null,
                null, null, null, null);
        }

        // ========= 第4优先级（兜底）：今日更新/推荐宽松匹配 =========

        // 4a. 先检查是否为"今日更新"类（"今日新番"/"今天更新的动漫"/"有什么新番"）
        boolean isTodayUpdate = text.startsWith("今天") || text.startsWith("今日");
        boolean hasUpdateKeyword = text.contains("更新") || text.contains("上新")
            || text.contains("新番") || text.contains("新动漫")
            || text.contains("新的番") || text.contains("新的动漫")
            || text.contains("有什么新") || text.contains("有啥新");
        boolean isBangumi = text.contains("动漫") || text.contains("番剧")
            || text.contains("番") && !text.contains("番号") && !text.contains("番茄");
        boolean isSeries = text.contains("剧集") || text.contains("电视剧")
            || (text.contains("剧") && !text.contains("番剧") && !text.contains("剧场")
                && !text.contains("喜剧") && !text.contains("剧情") && !text.contains("悲剧")
                && !text.contains("闹剧") && !text.contains("恶作剧"));

        if (isTodayUpdate && hasUpdateKeyword) {
            if (isBangumi && !isSeries) {
                return new ParsedCommand(CmdType.TODAY_UPDATES_ANIME,
                    null, null, ContentType.BANGUMI, null, null, null, null, null, null, null, null);
            }
            if (isSeries && !isBangumi) {
                return new ParsedCommand(CmdType.TODAY_UPDATES_SERIES,
                    null, null, ContentType.SERIES, null, null, null, null, null, null, null, null);
            }
            if (isBangumi) {
                return new ParsedCommand(CmdType.TODAY_UPDATES_ANIME,
                    null, null, ContentType.BANGUMI, null, null, null, null, null, null, null, null);
            }
        }

        // 4b. 旧有的推荐兜底
        boolean hasIntent = text.matches(".*?(推荐|推|好看|看看|有啥|找点|看点|来点|找|有没有|推荐一下|有什么|推荐点).*")
            || text.endsWith("看看") || text.endsWith("呗") || text.endsWith("啊") || text.endsWith("呢");
        boolean hasBangumi =
            text.contains("动漫") || text.contains("番剧")
                || (text.contains("番") && !text.contains("番号") && !text.contains("番茄") && hasIntent);
        boolean hasSeries =
            text.contains("剧集") || text.contains("电视剧") || text.contains("国产剧")
                || text.contains("美剧") || text.contains("日剧") || text.contains("韩剧")
                || (text.contains("剧") && !text.contains("番剧")  // 「番剧」不算剧集！排除！
                    && hasIntent
                    && !text.contains("剧场") && !text.contains("喜剧")
                    && !text.contains("剧情") && !text.contains("悲剧") && !text.contains("闹剧")
                    && !text.contains("恶作剧"));
        boolean hasMovie = text.contains("电影");

        if (hasIntent) {
            if (hasBangumi && !hasSeries && !hasMovie) {
                return new ParsedCommand(CmdType.TODAY_RECOMMEND_ANIME,
                    null, null, ContentType.BANGUMI, null, null, null, null, null, null, null, null);
            }
            if (hasSeries && !hasMovie && !hasBangumi) {
                return new ParsedCommand(CmdType.TODAY_RECOMMEND_SERIES,
                    null, null, ContentType.SERIES, null, null, null, null, null, null, null, null);
            }
            if (hasMovie && !hasBangumi && !hasSeries) {
                return new ParsedCommand(CmdType.TODAY_RECOMMEND_MOVIE,
                    null, null, ContentType.MOVIE, null, null, null, null, null, null, null, null);
            }
        }

        return ParsedCommand.unknown();
    }

    private static ParsedCommand parseRag(String text) {
        Matcher similar = RAG_SIMILAR.matcher(text);
        if (similar.matches()) {
            String title = similar.group(1) != null ? similar.group(1) : similar.group(2);
            return new ParsedCommand(
                CmdType.RAG_SIMILAR, null, null, typeOfNullable(similar.group(3)),
                null, null, null, null, cleanTitle(title), null, null, null);
        }
        similar = RAG_SIMILAR_PREFIX.matcher(text);
        if (similar.matches()) {
            String title = similar.group(1) != null ? similar.group(1) : similar.group(2);
            return new ParsedCommand(
                CmdType.RAG_SIMILAR, null, null, typeOfNullable(similar.group(3)),
                null, null, null, null, cleanTitle(title), null, null, null);
        }
        if (RAG_INTENT.matcher(text).matches()) {
            return new ParsedCommand(
                CmdType.RAG_QA, null, null, inferType(text), null, null, null,
                null, text, null, null, null);
        }
        return null;
    }

    private static ParsedCommand parseWeekdayPushPolicy(String text) {
        if (!text.contains("推送")) return null;
        boolean exclude = text.matches(".*不(?:要)?\\s*推送.*")
            || text.matches(".*(?:停止|暂停|取消|关闭).*推送.*");
        boolean include = text.matches(".*(?:恢复|开启|打开|重新开启).*推送.*");
        if (exclude == include) return null;

        Set<String> days = new LinkedHashSet<>();
        Matcher matcher = WEEKDAY.matcher(text);
        while (matcher.find()) days.add(dayName(matcher.group(1)));
        if (text.contains("周末")) {
            days.add("SATURDAY");
            days.add("SUNDAY");
        }
        if (days.isEmpty()) return null;

        return new ParsedCommand(
            CmdType.SET_WEEKDAY_PUSH_POLICY,
            null, null, inferType(text), null,
            "excluded_push_days", String.join(",", days), null,
            null, exclude ? "exclude" : "include", null, null);
    }

    private static ParsedCommand parseDailyRecommendation(String text) {
        if (text.contains("推送时间")) return null;
        if (!(text.contains("推送") || text.contains("推荐"))) return null;
        ContentType type = inferType(text);
        if (type == null) return null;
        ScheduleValue schedule = parseSchedule(text);
        if (schedule == null) return null;

        Double rating = null;
        Matcher ratingMatcher = RATING.matcher(text);
        if (ratingMatcher.find()) {
            rating = Double.parseDouble(ratingMatcher.group(1));
        } else if (text.contains("高分")) {
            rating = 9.0;
        }
        Integer count = null;
        Matcher countMatcher = COUNT.matcher(text);
        if (countMatcher.find()) count = Integer.parseInt(countMatcher.group(1));

        return new ParsedCommand(
            CmdType.CONFIGURE_DAILY_RECOMMENDATION,
            null, null, type, null, schedule.weekdays(),
            schedule.value(), true, null, schedule.kind(), rating, count);
    }

    private static ScheduleValue parseSchedule(String text) {
        Matcher relative = RELATIVE_TIME.matcher(text);
        if (relative.find()) {
            int amount = chineseNumber(relative.group(1));
            if (amount < 1) return null;
            LocalDateTime fireAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            fireAt = "小时".equals(relative.group(2))
                ? fireAt.plusHours(amount) : fireAt.plusMinutes(amount);
            return new ScheduleValue(
                "ONCE", String.valueOf(fireAt.atZone(
                    ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()), null);
        }

        LocalTime time = extractTime(text);
        if (time == null) return null;
        if (text.contains("明天") || text.contains("后天")) {
            int days = text.contains("后天") ? 2 : 1;
            LocalDateTime fireAt = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(days).atTime(time);
            return new ScheduleValue(
                "ONCE", String.valueOf(fireAt.atZone(
                    ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()), null);
        }

        if (text.contains("每周") || text.contains("每星期")
            || text.contains("每个星期")) {
            Set<String> days = new LinkedHashSet<>();
            Matcher weekday = WEEKDAY.matcher(text);
            while (weekday.find()) days.add(dayName(weekday.group(1)));
            if (days.isEmpty()) return null;
            return new ScheduleValue(
                "WEEKLY", time.toString(), String.join(",", days));
        }
        return new ScheduleValue("DAILY", time.toString(), null);
    }

    private static LocalTime extractTime(String text) {
        Matcher periodColon = PERIOD_COLON_TIME.matcher(text);
        if (periodColon.find()) {
            int hour = Integer.parseInt(periodColon.group(2));
            int minute = Integer.parseInt(periodColon.group(3));
            hour = adjustHour(periodColon.group(1), hour);
            return hour > 23 ? null : LocalTime.of(hour, minute);
        }
        Matcher quarter = QUARTER_TIME.matcher(text);
        if (quarter.find()) {
            int hour = adjustHour(quarter.group(1), chineseNumber(quarter.group(2)));
            int minute = switch (quarter.group(3)) {
                case "一刻" -> 15;
                case "三刻" -> 45;
                default -> 0;
            };
            return hour > 23 ? null : LocalTime.of(hour, minute);
        }
        Matcher arabic = ARABIC_TIME.matcher(text);
        if (arabic.find()) return LocalTime.parse(normalizeTime(arabic.group(1)));
        Matcher chinese = CHINESE_TIME.matcher(text);
        if (!chinese.find()) return null;
        int hour = chineseNumber(chinese.group(2));
        int minute = chinese.group(3) != null
            ? 30
            : chinese.group(4) == null ? 0 : chineseNumber(chinese.group(4));
        String period = chinese.group(1);
        hour = adjustHour(period, hour);
        if (hour > 23 || minute > 59) return null;
        return LocalTime.of(hour, minute);
    }

    private static int adjustHour(String period, int hour) {
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        if ("凌晨".equals(period) && hour == 12) hour = 0;
        return hour;
    }

    private record ScheduleValue(String kind, String value, String weekdays) {
    }

    private static int chineseNumber(String value) {
        if (value == null || value.isBlank()) return 0;
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
        for (int i = 0; i < normalized.length(); i++) {
            result = result * 10 + digit(normalized.charAt(i));
        }
        return result;
    }

    private static boolean looksLikeTimedPush(String text) {
        if (!(text.contains("推送") || text.contains("推荐"))) return false;
        if (inferType(text) == null) return false;
        return text.matches(".*(?:[0-9]{1,2}\\s*[点时]|[0-9]{1,2}[:：][0-9]{1,2}).*")
            || CHINESE_TIME.matcher(text).find()
            || RELATIVE_TIME.matcher(text).find()
            || text.contains("明天") || text.contains("后天")
            || text.contains("每周") || text.contains("每星期");
    }

    private static int parseIndex(String value) {
        return value.matches("\\d{1,2}")
            ? Integer.parseInt(value)
            : chineseNumber(value);
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static ParsedCommand preferenceCommand(
        CmdType type, ContentType contentType, String field, String value
    ) {
        return new ParsedCommand(
            type, null, null, contentType, null, field, value, null,
            null, null, null, null);
    }

    private static boolean containsRecommendationIntent(String text) {
        return text.matches(".*(推荐|好看|有啥|来点|找点|看看|推一下|推荐一下).*");
    }

    private static ContentType inferType(String text) {
        if (text.contains("电影")) return ContentType.MOVIE;
        if (text.contains("电视剧") || text.contains("剧集")
            || text.contains("美剧") || text.contains("日剧")
            || text.contains("韩剧") || text.contains("国产剧")
            || (text.contains("剧") && !text.contains("番剧")
                && !text.contains("剧场") && !text.contains("剧情")
                && !text.contains("喜剧") && !text.contains("悲剧"))) {
            return ContentType.SERIES;
        }
        if (text.contains("动漫") || text.contains("番剧")
            || (text.contains("番") && !text.contains("番号")
            && !text.contains("番茄"))) {
            return ContentType.BANGUMI;
        }
        return null;
    }

    private static ContentType typeOf(String value) {
        return switch (value) {
            case "电影" -> ContentType.MOVIE;
            case "剧集", "电视剧" -> ContentType.SERIES;
            default -> ContentType.BANGUMI;
        };
    }

    private static ContentType typeOfNullable(String value) {
        return value == null || value.isBlank() ? null : typeOf(value);
    }

    private static String normalizeTime(String value) {
        String[] parts = value.replace('：', ':').split(":");
        return String.format(
            "%02d:%02d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static String normalizeState(String value) {
        return switch (value) {
            case "想看" -> "want_to_watch";
            case "不喜欢" -> "disliked";
            default -> "watched";
        };
    }

    private static String dayName(String value) {
        return switch (value) {
            case "一" -> "MONDAY";
            case "二" -> "TUESDAY";
            case "三" -> "WEDNESDAY";
            case "四" -> "THURSDAY";
            case "五" -> "FRIDAY";
            case "六" -> "SATURDAY";
            default -> "SUNDAY";
        };
    }

    private static String cleanTitle(String title) {
        return title == null ? "" : title.trim()
            .replaceAll("^[《「『【]|[》」』】。！？]+$", "");
    }
}
