package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 供Agent内循环使用的B站Function Tool。
 *
 * <p>微信结构化命令通常由外层消息处理器直接处理；只有复杂对话才进入本工具。</p>
 */
@Component
public final class BilibiliTool implements FunctionTool {
    public static final String TOOL_NAME = "bilibili_manage";

    private final BilibiliCommandHandler commands;
    private final ObjectMapper mapper;
    private final AgentRequestContextHolder requestContextHolder;

    public BilibiliTool(
        BilibiliCommandHandler commands,
        ObjectMapper mapper,
        AgentRequestContextHolder requestContextHolder
    ) {
        this.commands = commands;
        this.mapper = mapper;
        this.requestContextHolder = requestContextHolder;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public JsonNode definition() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "function");
        ObjectNode function = root.putObject("function");
        function.put("name", TOOL_NAME);
        function.put("description",
            "管理B站动漫、电影和剧集的【立即】推荐、追更、搜索、标记操作。"
                + "不要传user_id，系统会绑定当前微信用户。"
                + "【重要】本工具只处理现在/马上/立即的即时操作；如果用户提到具体时间（如『11点推送』『每天几点推送』『明天推送』）要求定时推送，必须改用 scheduler_manage 工具创建定时任务（task_type=BILIBILI_PUSH），绝对不要使用本工具的 set_push_time、set_recommend_count、set_min_rating、enable_push 等推送设置 action——那些只会修改默认偏好，不会创建控制台可见的定时任务。");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        ArrayNode values = action.putArray("enum");
        for (String value : new String[] {
            "recommend_anime", "recommend_movie", "recommend_series",
            "subscribe_by_url", "subscribe_by_index", "subscribe_by_title",
            "search_by_title", "list_subscriptions", "check_updates",
            "mark_want", "mark_watched", "mark_disliked",
            "mark_want_by_title", "mark_watched_by_title",
            "mark_disliked_by_title",
            "today_updates_anime", "today_updates_series",
            "set_preferred_tags", "clear_preferred_tags",
            "get_preferred_tags", "remove_preferred_tag"
        }) values.add(value);
        properties.putObject("content_type")
            .put("type", "string")
            .put("description", "anime、movie或series");
        properties.putObject("url").put("type", "string");
        properties.putObject("tag")
            .put("type", "string")
            .put("description", "仅推荐时可用：指定推荐类型，如 热血、校园。不填则按用户偏好标签推荐。");
        properties.putObject("title")
            .put("type", "string")
            .put("description", "作品标题（搜索/订阅用）或偏好值（set_preferred_tags 时为逗号分隔的标签，如 热血,战斗）");
        properties.putObject("index").put("type", "integer");
        properties.putObject("push_time_hhmm")
            .put("type", "string")
            .put("description", "HH:mm格式");
        properties.putObject("minimum_rating").put("type", "number");
        properties.putObject("recommend_count").put("type", "integer");
        ObjectNode weekdays = properties.putObject("weekdays");
        weekdays.put("type", "array");
        weekdays.put("description", "要排除的星期，如[monday,saturday]");
        ArrayNode weekdayValues = weekdays.putObject("items")
            .put("type", "string").putArray("enum");
        for (String day : new String[] {
            "monday","tuesday","wednesday","thursday","friday","saturday","sunday"
        }) weekdayValues.add(day);
        parameters.putArray("required").add("action");
        parameters.put("additionalProperties", false);
        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String userId = requestContextHolder.currentUserId();
        if (userId.isBlank()) {
            return result(false, "当前工具调用缺少微信用户上下文");
        }
        String action = text(arguments, "action");
        String reply;
        try {
            reply = switch (action) {
                case "recommend_anime" ->
                    commands.handleTodayRecommend(userId, ContentType.BANGUMI,
                        text(arguments, "tag"));
                case "recommend_movie" ->
                    commands.handleTodayRecommend(userId, ContentType.MOVIE,
                        text(arguments, "tag"));
                case "recommend_series" ->
                    commands.handleTodayRecommend(userId, ContentType.SERIES,
                        text(arguments, "tag"));
                case "subscribe_by_url" ->
                    commands.handle(userId, require(arguments, "url"));
                case "subscribe_by_index" ->
                    commands.handleSubscribeByIndex(
                        userId, integer(arguments, "index"), type(arguments));
                case "subscribe_by_title" ->
                    commands.handleSubscribeByTitle(
                        userId, require(arguments, "title"));
                case "search_by_title" ->
                    commands.handleSearchByTitle(
                        userId, require(arguments, "title"));
                case "list_subscriptions" ->
                    commands.handle(userId, "我的订阅");
                case "check_updates" ->
                    commands.handle(userId, "检查更新");
                case "mark_want" ->
                    commands.handleMarkState(
                        userId, integer(arguments, "index"), "want_to_watch");
                case "mark_watched" ->
                    commands.handleMarkState(
                        userId, integer(arguments, "index"), "watched");
                case "mark_disliked" ->
                    commands.handleMarkState(
                        userId, integer(arguments, "index"), "disliked");
                case "mark_want_by_title" ->
                    commands.handleMarkStateByTitle(
                        userId, require(arguments, "title"), "want_to_watch");
                case "mark_watched_by_title" ->
                    commands.handleMarkStateByTitle(
                        userId, require(arguments, "title"), "watched");
                case "mark_disliked_by_title" ->
                    commands.handleMarkStateByTitle(
                        userId, require(arguments, "title"), "disliked");
                case "set_push_time" ->
                    commands.handleSetPreference(
                        userId, type(arguments), "push_time",
                        require(arguments, "push_time_hhmm"));
                case "set_min_rating" ->
                    commands.handleSetPreference(
                        userId, type(arguments), "min_rating",
                        require(arguments, "minimum_rating"));
                case "set_recommend_count" ->
                    commands.handleSetPreference(
                        userId, type(arguments), "count",
                        require(arguments, "recommend_count"));
                case "enable_push" ->
                    commands.handleTogglePush(userId, type(arguments), true);
                case "disable_push" ->
                    commands.handleTogglePush(userId, type(arguments), false);
                case "exclude_push_days" ->
                    commands.handleWeekdayPushPolicy(
                        userId, optionalType(arguments), weekdays(arguments), true);
                case "restore_push_days" ->
                    commands.handleWeekdayPushPolicy(
                        userId, optionalType(arguments), weekdays(arguments), false);
                case "today_updates_anime" ->
                    commands.handleTodayUpdates(userId, ContentType.BANGUMI);
                case "today_updates_series" ->
                    commands.handleTodayUpdates(userId, ContentType.SERIES);
                case "set_preferred_tags" -> {
                    String tags = text(arguments, "title");
                    if (tags.isBlank()) tags = text(arguments, "value");
                    if (tags.isBlank()) yield "❌ 请提供标签值，如：设置偏好标签 热血,战斗";
                    yield commands.handleSetPreference(
                        userId, type(arguments), "tags", tags);
                }
                case "clear_preferred_tags" ->
                    commands.handleSetPreference(
                        userId, type(arguments), "tags", "");
                case "get_preferred_tags" ->
                    commands.getPreferredTags(userId, type(arguments));
                case "remove_preferred_tag" ->
                    commands.handleSetPreference(
                        userId, type(arguments), "remove_tag",
                        require(arguments, "title"));
                default -> throw new IllegalArgumentException(
                    "未知操作类型：" + action);
            };
            return result(isSuccess(reply), reply);
        } catch (Exception error) {
            return result(false, error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private ContentType type(JsonNode arguments) {
        return switch (text(arguments, "content_type").toLowerCase()) {
            case "movie", "电影" -> ContentType.MOVIE;
            case "series", "剧集", "电视剧" -> ContentType.SERIES;
            default -> ContentType.BANGUMI;
        };
    }

    private ContentType optionalType(JsonNode arguments) {
        return text(arguments, "content_type").isBlank()
            ? null : type(arguments);
    }

    private Set<DayOfWeek> weekdays(JsonNode arguments) {
        JsonNode node = arguments == null ? null : arguments.get("weekdays");
        if (node == null || !node.isArray()) return Set.of();
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim().toLowerCase(Locale.ROOT);
            days.add(switch (value) {
                case "monday", "周一", "星期一" -> DayOfWeek.MONDAY;
                case "tuesday", "周二", "星期二" -> DayOfWeek.TUESDAY;
                case "wednesday", "周三", "星期三" -> DayOfWeek.WEDNESDAY;
                case "thursday", "周四", "星期四" -> DayOfWeek.THURSDAY;
                case "friday", "周五", "星期五" -> DayOfWeek.FRIDAY;
                case "saturday", "周六", "星期六" -> DayOfWeek.SATURDAY;
                case "sunday", "周日", "周天", "星期日", "星期天" ->
                    DayOfWeek.SUNDAY;
                default -> throw new IllegalArgumentException(
                    "无法识别星期：" + value);
            });
        }
        return days;
    }

    private Integer integer(JsonNode arguments, String field) {
        JsonNode node = arguments == null ? null : arguments.get(field);
        return node == null || !node.canConvertToInt() ? null : node.asInt();
    }

    private String require(JsonNode arguments, String field) {
        String value = text(arguments, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }

    private String text(JsonNode arguments, String field) {
        JsonNode node = arguments == null ? null : arguments.get(field);
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private boolean isSuccess(String reply) {
        return reply != null
            && !reply.startsWith("❌")
            && !reply.startsWith("[UNHANDLED");
    }

    private String result(boolean success, String reply) {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", success);
        result.put("reply_text", reply == null ? "" : reply);
        return result.toString();
    }
}
