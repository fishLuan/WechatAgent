package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BilibiliCommandParser {

    private BilibiliCommandParser() {}

    public enum CmdType {
        TODAY_RECOMMEND_ANIME,
        TODAY_RECOMMEND_MOVIE,
        TODAY_RECOMMEND_SERIES,
        SUBSCRIBE_BY_INDEX,
        MARK_WANT_TO_WATCH,
        MARK_WATCHED,
        MARK_DISLIKED,
        SUBSCRIBE_BY_URL,
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
        SEARCH_BY_TITLE,
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
        Boolean pushEnabled
    ) {
        public static ParsedCommand unknown() {
            return new ParsedCommand(CmdType.UNKNOWN, null, null, null, null, null, null, null);
        }
    }

    private static final Pattern IDX_PATTERN = Pattern.compile(
        "^(订阅|追更|想看|看过|不喜欢)\\s*(\\d{1,2})\\s*$");

    private static final Pattern LIST_SUB = Pattern.compile(
        "^(查看|我的|列出)?\\s*(订阅|追更列表|订阅列表)\\s*$");

    private static final Pattern CANCEL_SUB = Pattern.compile(
        "^(取消|删除|移除)\\s*订阅\\s*(\\d{1,2}|[0-9a-fA-F]{20,})?\\s*$");

    private static final Pattern PAUSE_RESUME_SUB = Pattern.compile(
        "^(暂停|恢复|继续)\\s*订阅\\s*(\\d{1,2}|[0-9a-fA-F]{20,})?\\s*$");

    private static final Pattern PUSH_TIME = Pattern.compile(
        "^设置(动漫|电影|剧集)推送时间\\s+([0-9]{1,2}[：:][0-9]{2})\\s*$");

    private static final Pattern MIN_RATING = Pattern.compile(
        "^设置(动漫|电影|剧集)最低评分\\s+(\\d+(?:\\.\\d+)?)\\s*$");

    private static final Pattern RECOMMEND_COUNT = Pattern.compile(
        "^设置(动漫|电影|剧集)推荐数量\\s+(\\d{1,2})\\s*$");

    private static final Pattern TOGGLE_PUSH = Pattern.compile(
        "^(开启|打开|启用|关闭|禁用|停止)\\s*(动漫|电影|剧集)(推送|每日推荐)?\\s*$");

    private static final Pattern BILIBILI_URL = Pattern.compile(
        "https?://(?:www\\.|m\\.)?bilibili\\.com/[a-zA-Z0-9/?=&%_.~#-]+" +
        "|https?://b23\\.tv/[a-zA-Z0-9]+");

    private static final Pattern SHOW_PREF = Pattern.compile(
        "^(查看|我的|显示|列出)\\s*(偏好|设置|推荐设置)\\s*$");

    private static final Pattern CHECK_NOW = Pattern.compile(
        "^(立即|马上|现在)?\\s*(检查更新|刷新更新|扫一下更新)\\s*$");

    private static final Pattern SEARCH_BY_TITLE = Pattern.compile(
        "^(?:搜索|查找|搜一下|找一下|帮我找(?:一下)?)\\s*(?:B站)?\\s*(.+?)\\s*$");

    public static ParsedCommand parse(String text) {
        if (text == null) return ParsedCommand.unknown();
        String t = text.trim();
        if (t.isBlank()) return ParsedCommand.unknown();
        t = t.replace('：', ':');

        Matcher m;

        // ========= 第1优先级：强约束结构化正则（精确匹配，不会误判） =========

        // 1. 订阅 / 想看 / 看过 / 不喜欢 加编号（^开头$结尾绝对约束）
        m = IDX_PATTERN.matcher(t);
        if (m.find()) {
            String action = m.group(1);
            int idx = Integer.parseInt(m.group(2));
            return switch (action) {
                case "订阅", "追更" -> new ParsedCommand(CmdType.SUBSCRIBE_BY_INDEX,
                    idx, null, null, null, null, null, null);
                case "想看" -> new ParsedCommand(CmdType.MARK_WANT_TO_WATCH,
                    idx, null, null, null, null, null, null);
                case "看过" -> new ParsedCommand(CmdType.MARK_WATCHED,
                    idx, null, null, null, null, null, null);
                default -> new ParsedCommand(CmdType.MARK_DISLIKED,
                    idx, null, null, null, null, null, null);
            };
        }

        // 2. 查看订阅
        if (LIST_SUB.matcher(t).find()) {
            return new ParsedCommand(CmdType.LIST_SUBSCRIPTIONS,
                null, null, null, null, null, null, null);
        }

        // 3. 取消订阅（编号 或 subscriptionId）
        m = CANCEL_SUB.matcher(t);
        if (m.find()) {
            String arg = m.group(2);
            if (arg == null) {
                return new ParsedCommand(CmdType.CANCEL_SUBSCRIPTION,
                    null, null, null, null, null, null, null);
            }
            if (arg.matches("\\d{1,2}")) {
                return new ParsedCommand(CmdType.CANCEL_SUBSCRIPTION,
                    Integer.parseInt(arg), null, null, null, null, null, null);
            }
            return new ParsedCommand(CmdType.CANCEL_SUBSCRIPTION,
                null, arg, null, null, null, null, null);
        }

        // 4. 暂停/恢复订阅
        m = PAUSE_RESUME_SUB.matcher(t);
        if (m.find()) {
            String op = m.group(1);
            String arg = m.group(2);
            CmdType ty = "暂停".equals(op) ? CmdType.PAUSE_SUBSCRIPTION : CmdType.RESUME_SUBSCRIPTION;
            if (arg == null) {
                return new ParsedCommand(ty, null, null, null, null, null, null, null);
            }
            if (arg.matches("\\d{1,2}")) {
                return new ParsedCommand(ty, Integer.parseInt(arg), null, null, null, null, null, null);
            }
            return new ParsedCommand(ty, null, arg, null, null, null, null, null);
        }

        // 5. 设置推送时间（^设置...推送时间$ 绝对结构）
        m = PUSH_TIME.matcher(t);
        if (m.find()) {
            ContentType ct = parseContentType(m.group(1));
            String v = normalizeHhmm(m.group(2));
            return new ParsedCommand(CmdType.SET_PUSH_TIME,
                null, null, ct, null, "push_time", v, null);
        }

        // 6. 设置最低评分
        m = MIN_RATING.matcher(t);
        if (m.find()) {
            ContentType ct = parseContentType(m.group(1));
            return new ParsedCommand(CmdType.SET_MIN_RATING,
                null, null, ct, null, "min_rating", m.group(2), null);
        }

        // 7. 设置推荐数量
        m = RECOMMEND_COUNT.matcher(t);
        if (m.find()) {
            ContentType ct = parseContentType(m.group(1));
            return new ParsedCommand(CmdType.SET_RECOMMEND_COUNT,
                null, null, ct, null, "count", m.group(2), null);
        }

        // 8. 开启/关闭推送
        m = TOGGLE_PUSH.matcher(t);
        if (m.find()) {
            boolean enable = m.group(1).startsWith("开") || m.group(1).startsWith("启");
            ContentType ct = parseContentType(m.group(2));
            return new ParsedCommand(CmdType.TOGGLE_PUSH,
                null, null, ct, null, null, null, enable);
        }

        // 9. 查看偏好设置
        if (SHOW_PREF.matcher(t).find()) {
            return new ParsedCommand(CmdType.SHOW_PREFERENCES,
                null, null, null, null, null, null, null);
        }

        // 10. 立即检查更新
        if (CHECK_NOW.matcher(t).find()) {
            return new ParsedCommand(CmdType.CHECK_UPDATES_NOW,
                null, null, null, null, null, null, null);
        }

        m = SEARCH_BY_TITLE.matcher(t);
        if (m.find() && !m.group(1).isBlank()) {
            return new ParsedCommand(CmdType.SEARCH_BY_TITLE,
                null, null, null, null, "title", m.group(1).trim(), null);
        }

        // ========= 第2优先级：今日推荐精确句型 =========
        if (t.equalsIgnoreCase("今日动漫推荐") || t.equals("动漫推荐")
            || t.equalsIgnoreCase("今天动漫推荐") || t.equalsIgnoreCase("今日番剧推荐")) {
            return new ParsedCommand(CmdType.TODAY_RECOMMEND_ANIME,
                null, null, ContentType.BANGUMI, null, null, null, null);
        }
        if (t.equalsIgnoreCase("今日剧集推荐") || t.equalsIgnoreCase("今日电视剧推荐")
            || t.equals("剧集推荐") || t.equals("电视剧推荐")) {
            return new ParsedCommand(CmdType.TODAY_RECOMMEND_SERIES,
                null, null, ContentType.SERIES, null, null, null, null);
        }
        if (t.equalsIgnoreCase("今日电影推荐") || t.equals("电影推荐")) {
            return new ParsedCommand(CmdType.TODAY_RECOMMEND_MOVIE,
                null, null, ContentType.MOVIE, null, null, null, null);
        }

        // ========= 第3优先级：B站链接（可能出现在推荐意图里但先当订阅处理） =========
        m = BILIBILI_URL.matcher(t);
        if (m.find()) {
            return new ParsedCommand(CmdType.SUBSCRIBE_BY_URL,
                null, null, null, m.group(), null, null, null);
        }

        // ========= 第4优先级（兜底）：今日推荐宽松匹配（未命中任何结构才到这） =========
        boolean hasIntent = t.matches(".*?(推荐|推|好看|看看|有啥|找点|看点|来点|找|有没有|推荐一下|有什么|推荐点).*")
            || t.endsWith("看看") || t.endsWith("呗") || t.endsWith("啊") || t.endsWith("呢");
        boolean hasBangumi =
            t.contains("动漫") || t.contains("番剧")
                || (t.contains("番") && !t.contains("番号") && !t.contains("番茄") && hasIntent);
        boolean hasSeries =
            t.contains("剧集") || t.contains("电视剧") || t.contains("国产剧")
                || t.contains("美剧") || t.contains("日剧") || t.contains("韩剧")
                || (t.contains("剧") && !t.contains("番剧")  // 「番剧」不算剧集！排除！
                    && hasIntent
                    && !t.contains("剧场") && !t.contains("喜剧")
                    && !t.contains("剧情") && !t.contains("悲剧") && !t.contains("闹剧")
                    && !t.contains("恶作剧"));
        boolean hasMovie = t.contains("电影");

        if (hasIntent) {
            if (hasBangumi && !hasSeries && !hasMovie) {
                return new ParsedCommand(CmdType.TODAY_RECOMMEND_ANIME,
                    null, null, ContentType.BANGUMI, null, null, null, null);
            }
            if (hasSeries && !hasMovie && !hasBangumi) {
                return new ParsedCommand(CmdType.TODAY_RECOMMEND_SERIES,
                    null, null, ContentType.SERIES, null, null, null, null);
            }
            if (hasMovie && !hasBangumi && !hasSeries) {
                return new ParsedCommand(CmdType.TODAY_RECOMMEND_MOVIE,
                    null, null, ContentType.MOVIE, null, null, null, null);
            }
        }

        return ParsedCommand.unknown();
    }

    private static ContentType parseContentType(String name) {
        if (name == null) return ContentType.BANGUMI;
        return switch (name) {
            case "动漫", "番", "番剧" -> ContentType.BANGUMI;
            case "剧集", "电视剧", "剧" -> ContentType.SERIES;
            case "电影" -> ContentType.MOVIE;
            default -> ContentType.BANGUMI;
        };
    }

    private static String normalizeHhmm(String v) {
        try {
            String[] parts = v.split("[:：]");
            int h = Integer.parseInt(parts[0]);
            int mm = Integer.parseInt(parts[1]);
            return String.format("%02d:%02d", Math.max(0, Math.min(23, h)),
                Math.max(0, Math.min(59, mm)));
        } catch (Exception e) {
            return v;
        }
    }
}
