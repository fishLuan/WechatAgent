package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class BilibiliTool implements FunctionTool {
    public static final String TOOL_NAME = "bilibili_manage";

    public static final InheritableThreadLocal<String> CURRENT_USER_ID = new InheritableThreadLocal<>();

    private final BilibiliCommandHandler commandHandler;
    private final ObjectMapper mapper;

    public BilibiliTool(@Lazy BilibiliCommandHandler commandHandler, ObjectMapper mapper) {
        this.commandHandler = commandHandler;
        this.mapper = mapper;
    }

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public JsonNode definition() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "function");
        ObjectNode func = root.putObject("function");
        func.put("name", TOOL_NAME);
        func.put("description",
            "B站动漫/剧集/电影推荐和追更订阅管理工具。当用户意图涉及B站内容时调用。\n" +
            "\n" +
            "【推荐类 action】推荐高分作品，不关心更新日期：\n" +
            "- recommend_anime：用户想看动漫推荐/高分番剧/找好看的番（『最近有啥好看的番』『推荐几部热血番』『找点好看的动漫』『今日动漫推荐』『动漫推荐』）\n" +
            "- recommend_movie：用户想看B站电影推荐（『推荐几部电影』『有什么好看的电影』）\n" +
            "- recommend_series：用户想看B站电视剧/剧集推荐（『推荐几部剧』『有啥好看的国产剧』）\n" +
            "\n" +
            "【今日更新类 action】严格只看今天新上线的集数，不关心评分：\n" +
            "- today_updates_anime：用户明确想看『今天更新了哪些动漫』『今天有什么新番』『今日更新的番剧』『今天上了什么动漫』——关键词是「今天/今日」+「更新/上新/新」+「番/动漫」\n" +
            "- today_updates_series：同上但针对电视剧/剧集（『今天更新了哪些剧』）\n" +
            "\n" +
            "【订阅管理类 action】\n" +
            "- subscribe_by_url：用户发了B站链接想追更\n" +
            "- subscribe_by_index：用户按推荐编号订阅（『订阅第2个』）\n" +
            "- subscribe_by_title：用户按作品名订阅（『订阅鬼灭之刃』）\n" +
            "- search_by_title：用户按作品名搜索\n" +
            "- list_subscriptions：查看我的订阅列表\n" +
            "- cancel/pause/resume_subscription：取消/暂停/恢复订阅\n" +
            "- check_updates_now：立即检查我的订阅有没有更新\n" +
            "\n" +
            "【标记类 action】mark_want_to_watch/mark_watched/mark_disliked（按编号或按标题）\n" +
            "\n" +
            "【设置类 action】set_push_time/set_min_rating/set_recommend_count/toggle_push_on/toggle_push_off/show_preferences\n" +
            "\n" +
            "⚠️ 重要区分：『今日推荐』/『推荐』→ recommend_anime；『今日更新』/『今天更新了什么』/『今天有什么新番』→ today_updates_anime。\n" +
            "注意：该工具不需要传user_id，系统自动识别当前对话用户。");

        ObjectNode params = func.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        ArrayNode enums = action.putArray("enum");
        enums.add("recommend_anime").add("recommend_movie").add("recommend_series")
             .add("subscribe_by_url").add("subscribe_by_index")
             .add("subscribe_by_title").add("search_by_title")
             .add("list_subscriptions").add("cancel_subscription")
             .add("pause_subscription").add("resume_subscription")
             .add("mark_want_to_watch").add("mark_watched").add("mark_disliked")
             .add("mark_want_to_watch_by_title")
             .add("mark_watched_by_title").add("mark_disliked_by_title")
             .add("set_push_time").add("set_min_rating").add("set_recommend_count")
             .add("toggle_push_on").add("toggle_push_off")
             .add("show_preferences").add("check_updates_now")
             .add("today_updates_anime").add("today_updates_series");
        action.put("description",
            "操作类型：\n" +
            "recommend_anime=高分动漫推荐（与更新日期无关）/ recommend_movie=电影推荐 / recommend_series=剧集推荐\n" +
            "today_updates_anime=今日新更新的动漫（只看今天上线的新集）/ today_updates_series=今日新更新的剧集\n" +
            "subscribe_by_url/by_index/by_title=订阅追更 / search_by_title=搜索作品\n" +
            "list_subscriptions/cancel_subscription/pause_subscription/resume_subscription=订阅管理\n" +
            "mark_want_to_watch/mark_watched/mark_disliked=按编号标记 / mark_xxx_by_title=按作品名标记\n" +
            "set_push_time/set_min_rating/set_recommend_count=偏好设置\n" +
            "toggle_push_on/toggle_push_off=开关推送 / show_preferences=查看偏好 / check_updates_now=检查订阅更新");

        ObjectNode urlNode = props.putObject("bilibili_url");
        urlNode.put("type", "string");
        urlNode.put("description", "[action=subscribe_by_url 时必填] B站作品链接，包含 bilibili.com 或 b23.tv");

        ObjectNode titleNode = props.putObject("title");
        titleNode.put("type", "string");
        titleNode.put("description",
            "[按作品名订阅、搜索或标记状态时必填] 用户提供的作品名称");

        ObjectNode idxNode = props.putObject("index");
        idxNode.put("type", "integer");
        idxNode.put("description", "[subscribe_by_index / cancel / pause / resume / mark_xxx 时必填] 推荐列表或订阅列表的编号，从1开始。用户说『订阅2』『看过3』『取消第一个』就分别填 2、3、1");

        ObjectNode subIdNode = props.putObject("subscription_id");
        subIdNode.put("type", "string");
        subIdNode.put("description", "[cancel/pause/resume 时可选，优先级低于index] 订阅ID（用户明确说订阅编号xx时填），优先用index，没有index再用subscription_id");

        ObjectNode ctNode = props.putObject("content_type");
        ctNode.put("type", "string");
        ArrayNode ctEnums = ctNode.putArray("enum");
        ctEnums.add("BANGUMI").add("SERIES").add("MOVIE");
        ctNode.put("description", "[set_xxx / recommend_xxx / toggle_push 时必填] 动漫番剧=BANGUMI / 电视剧剧集=SERIES / 电影=MOVIE。用户说『动漫』『番』就填BANGUMI，说『电影』就填MOVIE");

        ObjectNode pushTime = props.putObject("push_time_hhmm");
        pushTime.put("type", "string");
        pushTime.put("description", "[set_push_time 时必填] 每日推送时间，格式 HH:mm，示例 20:00、21:30、07:45");

        ObjectNode rating = props.putObject("min_rating");
        rating.put("type", "number");
        rating.put("description", "[set_min_rating 时必填] 最低评分 0-10，示例 9.0、8.5、7");

        ObjectNode count = props.putObject("recommend_count");
        count.put("type", "integer");
        count.put("description", "[set_recommend_count 时必填] 每次推荐几部，建议 1-10，示例 3、5、10");

        ArrayNode required = params.putArray("required");
        required.add("action");

        return root;
    }

    @Override
    public String execute(JsonNode args) {
        String userId = CURRENT_USER_ID.get();
        if (userId == null || userId.isBlank()) {
            userId = args.path("user_id").asText("");
        }
        ObjectNode res = mapper.createObjectNode();
        if (userId == null || userId.isBlank()) {
            res.put("success", false);
            res.put("reply_text", "❌ 识别不到当前对话用户，请在与我的对话中操作哦");
            return res.toString();
        }

        String action = args.path("action").asText("");
        String reply;
        try {
            reply = switch (action) {
                case "recommend_anime" -> commandHandler.handleTodayRecommend(userId, ContentType.BANGUMI);
                case "recommend_movie" -> commandHandler.handleTodayRecommend(userId, ContentType.MOVIE);
                case "recommend_series" -> commandHandler.handleTodayRecommend(userId, ContentType.SERIES);
                case "subscribe_by_url" -> commandHandler.handleSubscribeByUrl(userId, args.path("bilibili_url").asText(""));
                case "subscribe_by_title" ->
                    commandHandler.handleSubscribeByTitle(
                        userId, args.path("title").asText(""));
                case "search_by_title" ->
                    commandHandler.handleSearchByTitle(
                        userId, args.path("title").asText(""));
                case "subscribe_by_index" -> commandHandler.handleSubscribeByIndex(userId,
                    args.has("index") ? args.path("index").asInt(0) : null, parseType(args));
                case "list_subscriptions" -> commandHandler.handleListSubscriptions(userId);
                case "cancel_subscription" -> commandHandler.handleCancel(userId,
                    args.has("index") ? args.path("index").asInt(0) : null,
                    args.path("subscription_id").asText(null));
                case "pause_subscription" -> commandHandler.handlePause(userId,
                    args.has("index") ? args.path("index").asInt(0) : null,
                    args.path("subscription_id").asText(null));
                case "resume_subscription" -> commandHandler.handleResume(userId,
                    args.has("index") ? args.path("index").asInt(0) : null,
                    args.path("subscription_id").asText(null));
                case "mark_want_to_watch" -> commandHandler.handleMarkState(userId,
                    args.has("index") ? args.path("index").asInt(0) : null, "want_to_watch");
                case "mark_watched" -> commandHandler.handleMarkState(userId,
                    args.has("index") ? args.path("index").asInt(0) : null, "watched");
                case "mark_disliked" -> commandHandler.handleMarkState(userId,
                    args.has("index") ? args.path("index").asInt(0) : null, "disliked");
                case "mark_want_to_watch_by_title" ->
                    commandHandler.handleMarkStateByTitle(
                        userId, args.path("title").asText(""), "want_to_watch");
                case "mark_watched_by_title" ->
                    commandHandler.handleMarkStateByTitle(
                        userId, args.path("title").asText(""), "watched");
                case "mark_disliked_by_title" ->
                    commandHandler.handleMarkStateByTitle(
                        userId, args.path("title").asText(""), "disliked");
                case "set_push_time" -> commandHandler.handleSetPreference(userId, parseType(args),
                    "push_time", args.path("push_time_hhmm").asText(""));
                case "set_min_rating" -> commandHandler.handleSetPreference(userId, parseType(args),
                    "min_rating", String.valueOf(args.path("min_rating").asDouble(0)));
                case "set_recommend_count" -> commandHandler.handleSetPreference(userId, parseType(args),
                    "count", String.valueOf(args.path("recommend_count").asInt(0)));
                case "toggle_push_on" -> commandHandler.handleTogglePush(userId, parseType(args), true);
                case "toggle_push_off" -> commandHandler.handleTogglePush(userId, parseType(args), false);
                case "show_preferences" -> commandHandler.handleShowPreference(userId);
                case "check_updates_now" -> commandHandler.handleCheckUpdatesNow(userId);
                case "today_updates_anime" -> commandHandler.handleTodayUpdates(userId, ContentType.BANGUMI);
                case "today_updates_series" -> commandHandler.handleTodayUpdates(userId, ContentType.SERIES);
                default -> throw new IllegalArgumentException(
                    "未知操作类型：" + action);
            };
        } catch (Exception e) {
            res.put("success", false);
            res.put("reply_text", "❌ 操作失败：" + e.getMessage());
            return res.toString();
        }

        res.put("success", isSuccessfulReply(reply));
        res.put("reply_text", reply == null ? "" : reply);
        return res.toString();
    }

    private boolean isSuccessfulReply(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String normalized = reply.trim();
        return !normalized.startsWith("❌")
            && !normalized.startsWith("【UNHANDLED-");
    }

    private ContentType parseType(JsonNode args) {
        String ct = args.path("content_type").asText("");
        if (ct == null || ct.isBlank()) return ContentType.BANGUMI;
        try {
            return ContentType.valueOf(ct.trim().toUpperCase());
        } catch (Exception e) {
            return ContentType.BANGUMI;
        }
    }
}
