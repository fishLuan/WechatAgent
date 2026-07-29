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
            "B站动漫/剧集/电视剧/电影推荐和追更订阅管理工具。当用户表达以下任何意图时调用：\n" +
            "1) 想看今日推荐、高分推荐、找好看的动漫番剧/连载电视剧/院线电影（包含模糊表达如『最近有啥好看的番』『找点热血漫』『9分以上治愈电影』『最近热播的剧』『好看的国产剧推荐』）\n" +
            "2) 用户发送了B站作品链接（bilibili.com 或 b23.tv），想订阅/追更该作品\n" +
            "3) 用户想订阅/取消/暂停/恢复某个推荐编号的作品（『昨天推荐的第2个帮我订上』『取消第三个订阅』）\n" +
            "4) 用户想标记某部作品为『想看』『看过』『不喜欢』（『看过3』『不喜欢1』）\n" +
            "5) 用户想设置动漫/电影的每日推送时间、最低评分、推荐数量（『动漫改成每天21点推』『电影最低分调到9.0』）\n" +
            "6) 用户想开启/关闭每日推送、查看自己的偏好设置\n" +
            "7) 用户想查看自己订阅了哪些作品、想立即检查有没有新集更新\n" +
            "注意：该工具不需要传user_id，系统自动识别当前对话用户。");

        ObjectNode params = func.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        ArrayNode enums = action.putArray("enum");
        enums.add("recommend_anime").add("recommend_movie").add("recommend_series")
             .add("subscribe_by_url").add("subscribe_by_index")
             .add("list_subscriptions").add("cancel_subscription")
             .add("pause_subscription").add("resume_subscription")
             .add("mark_want_to_watch").add("mark_watched").add("mark_disliked")
             .add("set_push_time").add("set_min_rating").add("set_recommend_count")
             .add("toggle_push_on").add("toggle_push_off")
             .add("show_preferences").add("check_updates_now");
        action.put("description", "操作类型：recommend_anime=动漫推荐 / recommend_movie=电影推荐 / recommend_series=剧集电视剧推荐");

        ObjectNode urlNode = props.putObject("bilibili_url");
        urlNode.put("type", "string");
        urlNode.put("description", "[action=subscribe_by_url 时必填] B站作品链接，包含 bilibili.com 或 b23.tv");

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
                default -> {
                    res.put("success", false);
                    res.put("reply_text", "❌ 未知操作类型：" + action);
                    yield res.toString();
                }
            };
        } catch (Exception e) {
            res.put("success", false);
            res.put("reply_text", "❌ 操作失败：" + e.getMessage());
            return res.toString();
        }

        res.put("success", true);
        res.put("reply_text", reply == null ? "" : reply);
        return res.toString();
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
