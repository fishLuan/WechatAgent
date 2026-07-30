package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.time.LocalTime;
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
        TOGGLE_PUSH,
        SHOW_PREFERENCES,
        CHECK_UPDATES_NOW,
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
        "^(订阅|追更|想看|看过|不喜欢)\\s*(\\d{1,2})\\s*$");
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
        "^(?:搜索|查找|搜一下|找一下|帮我找(?:一下)?)\\s*(?:B站)?\\s*(.+?)\\s*$");
    private static final Pattern TITLE_SUBSCRIBE = Pattern.compile(
        "^(?:(?:我想|我要|请|帮我)\\s*)?(?:订阅|追更)\\s*(?:一下|下)?\\s*(?:作品)?\\s*(.+?)\\s*$");
    private static final Pattern TITLE_STATE = Pattern.compile(
        "^(?:我)?(?:已经|刚刚|刚)?\\s*(看过|看完了|想看|不喜欢)\\s*(?:了)?\\s*(.+?)\\s*$");
    private static final Pattern ARABIC_TIME = Pattern.compile(
        "(?:每天|每日)?.*?((?:[01]?\\d|2[0-3])[:：][0-5]\\d)");
    private static final Pattern RATING = Pattern.compile(
        "(10(?:\\.0)?|\\d(?:\\.\\d)?)\\s*分(?:以上|起)?");
    private static final Pattern COUNT = Pattern.compile("(\\d{1,2})\\s*(?:部|个)");
    private static final Pattern CHINESE_TIME = Pattern.compile(
        "(凌晨|早上|上午|中午|下午|晚上)?\\s*([零〇一二两三四五六七八九十]{1,3})点"
            + "(?:(半)|([零〇一二两三四五六七八九十]{1,3})分?)?");

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
                type, Integer.parseInt(matcher.group(2)), null, null,
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
        return ParsedCommand.unknown();
    }

    private static ParsedCommand parseDailyRecommendation(String text) {
        if (!(text.contains("每天") || text.contains("每日"))) return null;
        if (!(text.contains("推送") || text.contains("推荐"))) return null;
        ContentType type = inferType(text);
        if (type == null) return null;
        LocalTime time = extractTime(text);
        if (time == null) return null;

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
            null, null, type, null, "daily_recommendation",
            time.toString(), true, null, null, rating, count);
    }

    private static LocalTime extractTime(String text) {
        Matcher arabic = ARABIC_TIME.matcher(text);
        if (arabic.find()) return LocalTime.parse(normalizeTime(arabic.group(1)));
        Matcher chinese = CHINESE_TIME.matcher(text);
        if (!chinese.find()) return null;
        int hour = chineseNumber(chinese.group(2));
        int minute = chinese.group(3) != null
            ? 30
            : chinese.group(4) == null ? 0 : chineseNumber(chinese.group(4));
        String period = chinese.group(1);
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        if ("凌晨".equals(period) && hour == 12) hour = 0;
        if (hour > 23 || minute > 59) return null;
        return LocalTime.of(hour, minute);
    }

    private static int chineseNumber(String value) {
        if (value == null || value.isBlank()) return 0;
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

    private static String cleanTitle(String title) {
        return title == null ? "" : title.trim()
            .replaceAll("^[《「『【]|[》」』】。！？]+$", "");
    }
}
