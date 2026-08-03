package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.application.BilibiliCatalogCommandService;
import com.clawbot.wechatbot.feature.bilibili.application.BilibiliUpdateQueryService;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;
import com.clawbot.wechatbot.feature.bilibili.rag.BilibiliRagService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceCommandService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 微信命令入口：负责解析和路由，不承载搜索、更新查询等业务细节。 */
@Component
public final class BilibiliCommandHandler {
    private final BilibiliSubscriptionService subscriptions;
    private final BilibiliRecommendationService recommendations;
    private final BilibiliPreferenceService preferences;
    private final BilibiliPreferenceCommandService preferenceCommands;
    private final BilibiliCatalogCommandService catalogCommands;
    private final BilibiliUpdateQueryService updateQueries;
    private final WeChatSessionRegistry sessions;
    private final BilibiliRagService ragService;

    public BilibiliCommandHandler(
        @Lazy BilibiliSubscriptionService subscriptions,
        @Lazy BilibiliRecommendationService recommendations,
        BilibiliPreferenceService preferences,
        BilibiliPreferenceCommandService preferenceCommands,
        BilibiliCatalogCommandService catalogCommands,
        BilibiliUpdateQueryService updateQueries,
        WeChatSessionRegistry sessions,
        BilibiliRagService ragService
    ) {
        this.subscriptions = subscriptions;
        this.recommendations = recommendations;
        this.preferences = preferences;
        this.preferenceCommands = preferenceCommands;
        this.catalogCommands = catalogCommands;
        this.updateQueries = updateQueries;
        this.sessions = sessions;
        this.ragService = ragService;
    }

    public String handle(String userId, String input) {
        requireUser(userId);
        sessions.markActive(userId);
        BilibiliCommandParser.ParsedCommand command = BilibiliCommandParser.parse(input);
        try {
            return switch (command.type()) {
                case TODAY_RECOMMEND_ANIME -> handleTodayRecommend(userId, ContentType.BANGUMI);
                case TODAY_RECOMMEND_MOVIE -> handleTodayRecommend(userId, ContentType.MOVIE);
                case TODAY_RECOMMEND_SERIES -> handleTodayRecommend(userId, ContentType.SERIES);
                case CONFIGURE_DAILY_RECOMMENDATION ->
                    preferenceCommands.configureDaily(userId, command, input);
                case SUBSCRIBE_BY_INDEX ->
                    handleSubscribeByIndex(userId, command.index(), command.contentType());
                case SUBSCRIBE_BY_URL -> BilibiliMessageFormatter.formatSubscription(
                    subscriptions.subscribeByUrl(userId, command.url()));
                case SUBSCRIBE_BY_TITLE -> handleSubscribeByTitle(userId, command.title());
                case SEARCH_BY_TITLE -> handleSearchByTitle(userId, command.title());
                case MARK_TITLE -> handleMarkStateByTitle(userId, command.title(), command.state());
                case MARK_WANT_TO_WATCH -> handleMarkState(userId, command.index(), "want_to_watch");
                case MARK_WATCHED -> handleMarkState(userId, command.index(), "watched");
                case MARK_DISLIKED -> handleMarkState(userId, command.index(), "disliked");
                case LIST_SUBSCRIPTIONS -> BilibiliMessageFormatter.formatSubscriptions(
                    subscriptions.listSubscriptions(userId));
                case CANCEL_SUBSCRIPTION -> manageSubscription(userId, command, "cancel");
                case PAUSE_SUBSCRIPTION -> manageSubscription(userId, command, "pause");
                case RESUME_SUBSCRIPTION -> manageSubscription(userId, command, "resume");
                case SET_PUSH_TIME, SET_MIN_RATING, SET_RECOMMEND_COUNT ->
                    preferenceCommands.updateField(
                        userId, command.contentType(), command.fieldName(), command.fieldValue());
                case SET_WEEKDAY_PUSH_POLICY -> preferenceCommands.updateWeekdays(
                    userId, command.contentType(), parseDays(command.fieldValue()),
                    "exclude".equals(command.state()));
                case TOGGLE_PUSH -> preferenceCommands.toggle(
                    userId, command.contentType(), Boolean.TRUE.equals(command.pushEnabled()));
                case SHOW_PREFERENCES -> preferenceCommands.show(userId);
                case CHECK_UPDATES_NOW -> BilibiliMessageFormatter.formatCheckResult(
                    subscriptions.checkNow(userId));
                case TODAY_UPDATES_ANIME -> handleUpdates(
                    userId, ContentType.BANGUMI,
                    BilibiliUpdateRange.fromCommandValue(command.fieldValue()));
                case TODAY_UPDATES_SERIES -> handleUpdates(
                    userId, ContentType.SERIES,
                    BilibiliUpdateRange.fromCommandValue(command.fieldValue()));
                case RAG_QA -> ragService.answer(userId, command.title(), command.contentType());
                case RAG_SIMILAR ->
                    ragService.answerSimilar(userId, command.title(), command.contentType());
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
        RecommendationResult result = recommendations.recommend(userId, actualType, count);
        return BilibiliMessageFormatter.formatRecommendation(result);
    }

    public String handleSubscribeByIndex(String userId, Integer index, ContentType type) {
        sessions.markActive(userId);
        return catalogCommands.subscribeByIndex(userId, index, type);
    }

    public String handleSubscribeByTitle(String userId, String title) {
        sessions.markActive(userId);
        return catalogCommands.subscribeByTitle(userId, title);
    }

    public String handleSearchByTitle(String userId, String title) {
        sessions.markActive(userId);
        return catalogCommands.searchByTitle(userId, title);
    }

    public String handleMarkState(String userId, Integer index, String state) {
        sessions.markActive(userId);
        return catalogCommands.markByIndex(userId, index, state);
    }

    public String handleMarkStateByTitle(String userId, String title, String state) {
        sessions.markActive(userId);
        return catalogCommands.markByTitle(userId, title, state);
    }

    public String handleSetPreference(String userId, ContentType type, String key, String value) {
        sessions.markActive(userId);
        return preferenceCommands.updateField(userId, type, key, value);
    }

    public String handleTogglePush(String userId, ContentType type, Boolean enabled) {
        sessions.markActive(userId);
        return preferenceCommands.toggle(userId, type, Boolean.TRUE.equals(enabled));
    }

    public String handleWeekdayPushPolicy(
        String userId, ContentType type, Set<DayOfWeek> days, boolean excluded
    ) {
        sessions.markActive(userId);
        return preferenceCommands.updateWeekdays(userId, type, days, excluded);
    }

    public String handleTodayUpdates(String userId, ContentType contentType) {
        return handleUpdates(userId, contentType, BilibiliUpdateRange.TODAY);
    }

    public String handleUpdates(
        String userId, ContentType contentType, BilibiliUpdateRange range
    ) {
        sessions.markActive(userId);
        return updateQueries.query(userId, contentType, range);
    }

    public void syncRecommendationSchedule(
        String userId, ContentType contentType, LocalTime pushTime
    ) {
        try {
            preferenceCommands.syncDaily(userId, contentType, pushTime);
        } catch (Exception error) {
            System.err.println("[BILIBILI-SYNC-SCHEDULE] 失败: " + error.getMessage());
        }
    }

    private String manageSubscription(
        String userId, BilibiliCommandParser.ParsedCommand command, String operation
    ) {
        String id = command.subscriptionId();
        if (!hasText(id) && command.index() != null) {
            List<SubscriptionView> list = subscriptions.listSubscriptions(userId);
            int index = command.index();
            if (index < 1 || index > list.size()) return "❌ 订阅编号不存在。";
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

    private Set<DayOfWeek> parseDays(String value) {
        if (!hasText(value)) return Set.of();
        Set<DayOfWeek> days = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(day -> !day.isEmpty())
            .map(DayOfWeek::valueOf)
            .forEach(days::add);
        return days;
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
