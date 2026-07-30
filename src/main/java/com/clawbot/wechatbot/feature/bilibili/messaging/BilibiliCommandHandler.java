package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.PreferenceUpdate;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.RecommendationHistoryService;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 角色五的应用服务：只编排公共领域接口，不访问仓储和页面解析器。
 */
@Component
public final class BilibiliCommandHandler {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final BilibiliSubscriptionService subscriptions;
    private final BilibiliRecommendationService recommendations;
    private final BilibiliPreferenceService preferences;
    private final WeChatSessionRegistry sessions;
    private final BilibiliContentSource contentSource;
    private final BilibiliProperties properties;
    private final RecommendationHistoryService history;

    public BilibiliCommandHandler(
        @Lazy BilibiliSubscriptionService subscriptions,
        @Lazy BilibiliRecommendationService recommendations,
        BilibiliPreferenceService preferences,
        SchedulerControlService ignoredScheduler,
        WeChatSessionRegistry sessions,
        ObjectMapper ignoredMapper,
        BilibiliContentSource contentSource,
        BilibiliProperties properties,
        RecommendationHistoryService history
    ) {
        this.subscriptions = subscriptions;
        this.recommendations = recommendations;
        this.preferences = preferences;
        this.sessions = sessions;
        this.contentSource = contentSource;
        this.properties = properties;
        this.history = history;
    }

    public String handle(String userId, String input) {
        requireUser(userId);
        sessions.markActive(userId);
        BilibiliCommandParser.ParsedCommand command =
            BilibiliCommandParser.parse(input);
        try {
            return switch (command.type()) {
                case TODAY_RECOMMEND_ANIME ->
                    handleTodayRecommend(userId, ContentType.BANGUMI);
                case TODAY_RECOMMEND_MOVIE ->
                    handleTodayRecommend(userId, ContentType.MOVIE);
                case TODAY_RECOMMEND_SERIES ->
                    handleTodayRecommend(userId, ContentType.SERIES);
                case CONFIGURE_DAILY_RECOMMENDATION ->
                    handleDailyConfiguration(userId, command);
                case SUBSCRIBE_BY_INDEX ->
                    handleSubscribeByIndex(userId, command.index(), command.contentType());
                case SUBSCRIBE_BY_URL ->
                    BilibiliMessageFormatter.formatSubscription(
                        subscriptions.subscribeByUrl(userId, command.url()));
                case SUBSCRIBE_BY_TITLE ->
                    handleSubscribeByTitle(userId, command.title());
                case SEARCH_BY_TITLE ->
                    handleSearchByTitle(userId, command.title());
                case MARK_TITLE ->
                    handleMarkStateByTitle(userId, command.title(), command.state());
                case MARK_WANT_TO_WATCH ->
                    handleMarkState(userId, command.index(), "want_to_watch");
                case MARK_WATCHED ->
                    handleMarkState(userId, command.index(), "watched");
                case MARK_DISLIKED ->
                    handleMarkState(userId, command.index(), "disliked");
                case LIST_SUBSCRIPTIONS ->
                    BilibiliMessageFormatter.formatSubscriptions(
                        subscriptions.listSubscriptions(userId));
                case CANCEL_SUBSCRIPTION ->
                    manageSubscription(userId, command, "cancel");
                case PAUSE_SUBSCRIPTION ->
                    manageSubscription(userId, command, "pause");
                case RESUME_SUBSCRIPTION ->
                    manageSubscription(userId, command, "resume");
                case SET_PUSH_TIME, SET_MIN_RATING, SET_RECOMMEND_COUNT ->
                    handleSetPreference(
                        userId, command.contentType(),
                        command.fieldName(), command.fieldValue());
                case TOGGLE_PUSH ->
                    handleTogglePush(
                        userId, command.contentType(), command.pushEnabled());
                case SHOW_PREFERENCES -> handleShowPreferences(userId);
                case CHECK_UPDATES_NOW ->
                    BilibiliMessageFormatter.formatCheckResult(
                        subscriptions.checkNow(userId));
                case UNKNOWN -> "[UNHANDLED-BILIBILI-UNKNOWN]";
            };
        } catch (Exception error) {
            return failure("B站请求处理失败", error);
        }
    }

    public String handleTodayRecommend(String userId, ContentType type) {
        requireUser(userId);
        sessions.markActive(userId);
        ContentType actualType = type == null ? ContentType.BANGUMI : type;
        BilibiliPreference preference = preferences.getOrCreate(userId, actualType);
        int count = Math.max(1, preference.getRecommendationCount());
        RecommendationResult result =
            recommendations.recommend(userId, actualType, count);
        return BilibiliMessageFormatter.formatRecommendation(result);
    }

    public String handleSubscribeByIndex(
        String userId, Integer index, ContentType ignoredType
    ) {
        sessions.markActive(userId);
        if (index == null || index < 1) return "❌ 推荐编号不正确。";
        RecommendedContent item = recommendations.findPendingItem(userId, index);
        if (item == null) {
            return "❌ 找不到第 " + index + " 个推荐，请先获取最新推荐。";
        }
        SubscriptionResult result;
        if (hasText(item.seasonId())) {
            result = subscriptions.subscribeBySeasonId(
                userId, item.contentType(), item.seasonId());
        } else {
            result = subscriptions.subscribeByContentId(
                userId, item.contentType(), item.contentId());
        }
        return BilibiliMessageFormatter.formatSubscription(result);
    }

    public String handleSubscribeByTitle(String userId, String title) {
        sessions.markActive(userId);
        try {
            List<BilibiliContent> matches = search(title);
            if (matches.isEmpty()) {
                return "❌ 没有找到作品“" + title + "”。";
            }
            BilibiliContent exact = uniqueExactMatch(title, matches);
            if (exact == null) {
                return "找到多个相关作品，请发送其中一个链接完成订阅：\n\n"
                    + BilibiliMessageFormatter.formatSearchResults(title, matches);
            }
            if (exact.isFinished()) {
                return "ℹ️ 《" + exact.getTitle() + "》已经完结，无需追更订阅。";
            }
            SubscriptionResult result = hasText(exact.getSeasonId())
                ? subscriptions.subscribeBySeasonId(
                    userId, exact.getContentType(), exact.getSeasonId())
                : subscriptions.subscribeByContentId(
                    userId, exact.getContentType(), exact.getContentId());
            return BilibiliMessageFormatter.formatSubscription(result);
        } catch (Exception error) {
            return failure("按名称订阅失败", error);
        }
    }

    public String handleSearchByTitle(String userId, String title) {
        sessions.markActive(userId);
        try {
            return BilibiliMessageFormatter.formatSearchResults(title, search(title));
        } catch (Exception error) {
            return failure("搜索作品失败", error);
        }
    }

    public String handleMarkState(
        String userId, Integer index, String state
    ) {
        sessions.markActive(userId);
        if (index == null || index < 1) return "❌ 要标记的编号不正确。";
        RecommendedContent item = recommendations.findPendingItem(userId, index);
        if (item == null) {
            return "❌ 找不到第 " + index + " 个推荐，请先获取最新推荐。";
        }
        switch (normalizeState(state)) {
            case "want_to_watch" -> history.markWantToWatch(
                userId, item.contentType(), item.contentId(), item.title());
            case "watched" -> recommendations.markWatched(userId, index);
            case "disliked" -> recommendations.markDisliked(userId, index);
            default -> throw new IllegalArgumentException("未知标记状态");
        }
        return "✅ 已将《" + item.title() + "》标记"
            + stateDescription(state) + "。";
    }

    public String handleMarkStateByTitle(
        String userId, String title, String state
    ) {
        sessions.markActive(userId);
        try {
            List<BilibiliContent> matches = search(title);
            BilibiliContent exact = uniqueExactMatch(title, matches);
            if (exact == null) {
                if (matches.isEmpty()) return "❌ 没有找到作品“" + title + "”。";
                return "找到多个相关作品，无法确定你指的是哪一个：\n\n"
                    + BilibiliMessageFormatter.formatSearchResults(title, matches);
            }
            switch (normalizeState(state)) {
                case "want_to_watch" -> history.markWantToWatch(
                    userId, exact.getContentType(), exact.getContentId(), exact.getTitle());
                case "watched" -> history.markWatched(
                    userId, exact.getContentType(), exact.getContentId(), exact.getTitle());
                case "disliked" -> history.markDisliked(
                    userId, exact.getContentType(), exact.getContentId(), exact.getTitle());
                default -> throw new IllegalArgumentException("未知标记状态");
            }
            return "✅ 已将《" + exact.getTitle() + "》标记为"
                + stateDescription(state) + "。";
        } catch (Exception error) {
            return failure("标记作品失败", error);
        }
    }

    public String handleSetPreference(
        String userId, ContentType type, String key, String value
    ) {
        sessions.markActive(userId);
        ContentType actualType = type == null ? ContentType.BANGUMI : type;
        BilibiliPreference current = preferences.getOrCreate(userId, actualType);
        double rating = current.getMinimumRating();
        int count = current.getRecommendationCount();
        LocalTime pushTime = current.getPushTime();
        String field;
        String display;
        switch (key) {
            case "push_time" -> {
                pushTime = LocalTime.parse(value, HH_MM);
                field = "每日推送时间";
                display = pushTime.format(HH_MM);
            }
            case "min_rating" -> {
                rating = Double.parseDouble(value);
                if (rating < 0 || rating > 10) {
                    return "❌ 最低评分必须在 0 到 10 之间。";
                }
                field = "最低评分";
                display = rating + " 分";
            }
            case "count" -> {
                count = Integer.parseInt(value);
                if (count < 1 || count > 10) {
                    return "❌ 推荐数量必须在 1 到 10 之间。";
                }
                field = "推荐数量";
                display = count + " 部";
            }
            default -> {
                return "❌ 未知设置项：" + key;
            }
        }
        preferences.update(
            userId, actualType,
            new PreferenceUpdate(
                rating, count, pushTime, safeGenres(current), current.isPushEnabled()));
        return BilibiliMessageFormatter.formatPreferenceUpdated(
            BilibiliMessageFormatter.typeName(actualType), field, display);
    }

    public String handleTogglePush(
        String userId, ContentType type, Boolean enabled
    ) {
        sessions.markActive(userId);
        ContentType actualType = type == null ? ContentType.BANGUMI : type;
        boolean actualEnabled = Boolean.TRUE.equals(enabled);
        preferences.setPushEnabled(userId, actualType, actualEnabled);
        return "✅ " + BilibiliMessageFormatter.typeName(actualType)
            + "每日推送已" + (actualEnabled ? "开启" : "关闭") + "。";
    }

    private String handleDailyConfiguration(
        String userId, BilibiliCommandParser.ParsedCommand command
    ) {
        ContentType type = command.contentType();
        BilibiliPreference current = preferences.getOrCreate(userId, type);
        LocalTime time = LocalTime.parse(command.fieldValue(), HH_MM);
        double rating = command.minimumRating() == null
            ? current.getMinimumRating() : command.minimumRating();
        int count = command.recommendationCount() == null
            ? current.getRecommendationCount() : command.recommendationCount();
        if (rating < 0 || rating > 10 || count < 1 || count > 10) {
            return "❌ 推送条件不合法：评分需为0～10，数量需为1～10。";
        }
        BilibiliPreference saved = preferences.update(
            userId, type,
            new PreferenceUpdate(
                rating, count, time, safeGenres(current), true));
        return "✅ 已设置每天 " + saved.getPushTime().format(HH_MM)
            + " 推送 " + saved.getRecommendationCount() + " 部高分"
            + BilibiliMessageFormatter.typeName(type)
            + "（最低 " + saved.getMinimumRating() + " 分）。";
    }

    private String handleShowPreferences(String userId) {
        StringBuilder out = new StringBuilder("B站推荐设置\n\n");
        for (ContentType type : ContentType.values()) {
            out.append(BilibiliMessageFormatter.formatPreference(
                preferences.getOrCreate(userId, type))).append("\n\n");
        }
        return out.toString().trim();
    }

    private String manageSubscription(
        String userId,
        BilibiliCommandParser.ParsedCommand command,
        String operation
    ) {
        String id = command.subscriptionId();
        if (!hasText(id) && command.index() != null) {
            List<SubscriptionView> list = subscriptions.listSubscriptions(userId);
            int index = command.index();
            if (index < 1 || index > list.size()) {
                return "❌ 订阅编号不存在。";
            }
            id = list.get(index - 1).subscriptionId();
        }
        if (!hasText(id)) return "❌ 请指定订阅编号。";
        OperationResult result = switch (operation) {
            case "pause" -> subscriptions.pause(userId, id);
            case "resume" -> subscriptions.resume(userId, id);
            default -> subscriptions.cancel(userId, id);
        };
        return (result.success() ? "✅ " : "❌ ") + result.message();
    }

    private List<BilibiliContent> search(String title) throws Exception {
        if (!hasText(title)) return List.of();
        return contentSource.searchByTitle(title.trim(), properties.getSearchResultCount());
    }

    private BilibiliContent uniqueExactMatch(
        String requestedTitle, List<BilibiliContent> matches
    ) {
        if (matches == null || matches.isEmpty()) return null;
        String requested = normalizedTitle(requestedTitle);
        List<BilibiliContent> exact = matches.stream()
            .filter(item -> normalizedTitle(item.getTitle()).equals(requested))
            .toList();
        if (exact.size() == 1) return exact.get(0);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String normalizedTitle(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
            .replace("的", "")
            .replaceAll("[\\s·:：,，。！？《》【】()（）\\-—_]", "");
    }

    private String normalizeState(String state) {
        if (state == null) return "";
        return switch (state) {
            case "want", "want_to_watch", "想看" -> "want_to_watch";
            case "watched", "看过", "看完了" -> "watched";
            case "dislike", "disliked", "不喜欢" -> "disliked";
            default -> state;
        };
    }

    private String stateDescription(String state) {
        return switch (normalizeState(state)) {
            case "want_to_watch" -> "想看";
            case "watched" -> "看过";
            case "disliked" -> "不喜欢";
            default -> "已处理";
        };
    }

    private Set<String> safeGenres(BilibiliPreference preference) {
        return preference.getPreferredGenres() == null
            ? Set.of() : preference.getPreferredGenres();
    }

    private void requireUser(String userId) {
        if (!hasText(userId)) throw new IllegalArgumentException("微信用户 ID 不能为空");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String failure(String action, Exception error) {
        String reason = error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage();
        System.err.println("[BILIBILI] " + action + "：" + reason);
        return "❌ " + action + "：" + reason;
    }
}
