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
import com.clawbot.wechatbot.feature.bilibili.rag.BilibiliRagService;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
    private final PendingSearchResultStore pendingSearchResults;
    private final BilibiliRagService ragService;
    private final BilibiliContentRepository contentRepository;
    private final SchedulerControlService schedulerService;
    private final ObjectMapper objectMapper;

    public BilibiliCommandHandler(
        @Lazy BilibiliSubscriptionService subscriptions,
        @Lazy BilibiliRecommendationService recommendations,
        BilibiliPreferenceService preferences,
        SchedulerControlService ignoredScheduler,
        WeChatSessionRegistry sessions,
        ObjectMapper ignoredMapper,
        BilibiliContentSource contentSource,
        BilibiliProperties properties,
        RecommendationHistoryService history,
        PendingSearchResultStore pendingSearchResults,
        BilibiliRagService ragService,
        BilibiliContentRepository contentRepository
    ) {
        this.subscriptions = subscriptions;
        this.recommendations = recommendations;
        this.preferences = preferences;
        this.sessions = sessions;
        this.contentSource = contentSource;
        this.properties = properties;
        this.history = history;
        this.pendingSearchResults = pendingSearchResults;
        this.ragService = ragService;
        this.contentRepository = contentRepository;
        this.schedulerService = ignoredScheduler;
        this.objectMapper = ignoredMapper;
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
                    handleDailyConfiguration(userId, command, input);
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
                case SET_WEEKDAY_PUSH_POLICY ->
                    handleWeekdayPushPolicy(
                        userId,
                        command.contentType(),
                        parseDays(command.fieldValue()),
                        "exclude".equals(command.state()));
                case TOGGLE_PUSH ->
                    handleTogglePush(
                        userId, command.contentType(), command.pushEnabled());
                case SHOW_PREFERENCES -> handleShowPreferences(userId);
                case CHECK_UPDATES_NOW ->
                    BilibiliMessageFormatter.formatCheckResult(
                        subscriptions.checkNow(userId));
                case TODAY_UPDATES_ANIME ->
                    handleTodayUpdates(userId, ContentType.BANGUMI);
                case TODAY_UPDATES_SERIES ->
                    handleTodayUpdates(userId, ContentType.SERIES);
                case RAG_QA ->
                    ragService.answer(userId, command.title(), command.contentType());
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
        RecommendationResult result =
            recommendations.recommend(userId, actualType, count);
        return BilibiliMessageFormatter.formatRecommendation(result);
    }

    public String handleSubscribeByIndex(
        String userId, Integer index, ContentType ignoredType
    ) {
        sessions.markActive(userId);
        if (index == null || index < 1) return "❌ 推荐编号不正确。";
        BilibiliContent searchedItem =
            pendingSearchResults.findByItemNumber(userId, index);
        if (searchedItem != null) {
            if (searchedItem.isFinished()) {
                return "ℹ️ 《" + searchedItem.getTitle()
                    + "》已经完结，无需追更订阅。";
            }
            SubscriptionResult result = hasText(searchedItem.getSeasonId())
                ? subscriptions.subscribeBySeasonId(
                    userId,
                    searchedItem.getContentType(),
                    searchedItem.getSeasonId())
                : subscriptions.subscribeByContentId(
                    userId,
                    searchedItem.getContentType(),
                    searchedItem.getContentId());
            return BilibiliMessageFormatter.formatSubscription(result);
        }
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
                pendingSearchResults.put(userId, matches);
                return "找到多个相关作品，请回复“订阅第几个”或发送对应链接：\n\n"
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
            List<BilibiliContent> matches = search(title);
            pendingSearchResults.put(userId, matches);
            return BilibiliMessageFormatter.formatSearchResults(title, matches);
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

    public String handleWeekdayPushPolicy(
        String userId,
        ContentType type,
        Set<DayOfWeek> days,
        boolean excluded
    ) {
        sessions.markActive(userId);
        if (days == null || days.isEmpty()) {
            return "❌ 请指定需要设置的星期。";
        }
        List<ContentType> types = type == null
            ? List.of(ContentType.BANGUMI, ContentType.SERIES, ContentType.MOVIE)
            : List.of(type);
        for (ContentType actualType : types) {
            preferences.setExcludedPushDays(
                userId, actualType, days, excluded);
        }
        String target = type == null
            ? "动漫、剧集和电影"
            : BilibiliMessageFormatter.typeName(type);
        return "✅ 已设置" + target + "在" + formatDays(days)
            + (excluded ? "不发送每日推荐。" : "恢复每日推荐。");
    }

    private String handleDailyConfiguration(
        String userId,
        BilibiliCommandParser.ParsedCommand command,
        String originalInput
    ) {
        List<ContentType> types = configuredContentTypes(
            originalInput, command.contentType());
        if ("ONCE".equals(command.state())) {
            return createOneTimeRecommendation(userId, command, types);
        }
        LocalTime time = LocalTime.parse(command.fieldValue(), HH_MM);
        List<BilibiliPreference> savedPreferences = new java.util.ArrayList<>();
        for (ContentType type : types) {
            BilibiliPreference current = preferences.getOrCreate(userId, type);
            double rating = command.minimumRating() == null
                ? current.getMinimumRating() : command.minimumRating();
            int count = command.recommendationCount() == null
                ? current.getRecommendationCount() : command.recommendationCount();
            if (rating < 0 || rating > 10 || count < 1 || count > 10) {
                return "❌ 推送条件不合法：评分需为0～10，数量需为1～10。";
            }
            savedPreferences.add(preferences.update(
                userId, type,
                new PreferenceUpdate(
                    rating, count, time, safeGenres(current), true)));
            if ("WEEKLY".equals(command.state())) {
                Set<DayOfWeek> included = parseDays(command.fieldName());
                Set<DayOfWeek> excluded = new LinkedHashSet<>(
                    Set.of(DayOfWeek.values()));
                excluded.removeAll(included);
                preferences.setExcludedPushDays(userId, type,
                    Set.of(DayOfWeek.values()), false);
                preferences.setExcludedPushDays(userId, type, excluded, true);
            }
        }
        if (savedPreferences.size() == 1) {
            BilibiliPreference saved = savedPreferences.get(0);
            ContentType type = saved.getContentType();
            String frequency = "WEEKLY".equals(command.state())
                ? "每" + formatDays(parseDays(command.fieldName())) + " " : "每天 ";
            return "✅ 已设置" + frequency + saved.getPushTime().format(HH_MM)
                + " 推送 " + saved.getRecommendationCount() + " 部高分"
                + BilibiliMessageFormatter.typeName(type)
                + "（最低 " + saved.getMinimumRating() + " 分）。";
        }
        String prefix = "WEEKLY".equals(command.state())
            ? "✅ 已设置每" + formatDays(parseDays(command.fieldName())) + " "
            : "✅ 已设置每天 ";
        StringBuilder reply = new StringBuilder(prefix)
            .append(time.format(HH_MM)).append(" 推送：");
        for (BilibiliPreference saved : savedPreferences) {
            reply.append("\n- ")
                .append(saved.getRecommendationCount()).append(" 部高分")
                .append(BilibiliMessageFormatter.typeName(saved.getContentType()))
                .append("（最低 ").append(saved.getMinimumRating()).append(" 分）");
        }
        return reply.toString();
    }

    private String createOneTimeRecommendation(
        String userId,
        BilibiliCommandParser.ParsedCommand command,
        List<ContentType> types
    ) {
        long fireAt = Long.parseLong(command.fieldValue());
        if (fireAt <= System.currentTimeMillis()) {
            return "❌ 推送时间必须晚于当前时间。";
        }
        for (ContentType type : types) {
            BilibiliPreference preference = preferences.getOrCreate(userId, type);
            int count = command.recommendationCount() == null
                ? Math.max(1, preference.getRecommendationCount())
                : command.recommendationCount();
            ScheduledSubscription task = new ScheduledSubscription();
            task.setUserId(userId);
            task.setTaskType(TaskType.BILIBILI_RECOMMENDATION);
            task.setCronExpression("");
            com.fasterxml.jackson.databind.node.ObjectNode params =
                objectMapper.createObjectNode();
            params.put("fire_timestamp", fireAt);
            params.put("already_fired", false);
            params.put("bilibili_content_type", type.name());
            params.put("recommendation_count", count);
            task.setParamsJson(params.toString());
            task.setEnabled(true);
            schedulerService.createOrUpdate(task);
        }
        String time = java.time.LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fireAt), ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String names = types.stream()
            .map(BilibiliMessageFormatter::typeName)
            .reduce((left, right) -> left + "和" + right)
            .orElse("内容");
        return "✅ 已设置一次性任务：" + time + " 推送" + names + "。";
    }

    private List<ContentType> configuredContentTypes(
        String input, ContentType fallback
    ) {
        String text = input == null ? "" : input;
        LinkedHashSet<ContentType> types = new LinkedHashSet<>();
        if (text.contains("动漫") || text.contains("番剧")) {
            types.add(ContentType.BANGUMI);
        }
        if (text.contains("电视剧") || text.contains("剧集")
            || text.contains("美剧") || text.contains("日剧")
            || text.contains("韩剧") || text.contains("国产剧")) {
            types.add(ContentType.SERIES);
        }
        if (text.contains("电影")) {
            types.add(ContentType.MOVIE);
        }
        if (types.isEmpty() && fallback != null) {
            types.add(fallback);
        }
        return List.copyOf(types);
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

    private String formatDays(Set<DayOfWeek> days) {
        return days.stream()
            .sorted()
            .map(day -> switch (day) {
                case MONDAY -> "周一";
                case TUESDAY -> "周二";
                case WEDNESDAY -> "周三";
                case THURSDAY -> "周四";
                case FRIDAY -> "周五";
                case SATURDAY -> "周六";
                case SUNDAY -> "周日";
            })
            .reduce((left, right) -> left + "、" + right)
            .orElse("");
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

    /* ========== 8. 今日更新推荐 ========== */
    public String handleTodayUpdates(String userId, ContentType contentType) {
        sessions.markActive(userId);
        if (contentType == null) contentType = ContentType.BANGUMI;
        try {
            List<BilibiliContent> updatedToday;
            ZoneId bj = ZoneId.of("Asia/Shanghai");
            Instant todayStart = LocalDate.now(bj).atStartOfDay(bj).toInstant();

            updatedToday = contentRepository.findTodayUpdates(contentType, todayStart);
            if (updatedToday != null && !updatedToday.isEmpty()) {
                System.out.println("[BILIBILI] DB pubTime 查询命中 " + updatedToday.size() + " 条");
            } else {
                try {
                    updatedToday = contentSource.findTodayAiring(contentType);
                    System.out.println("[BILIBILI] PGC 索引返回 "
                        + (updatedToday == null ? 0 : updatedToday.size()) + " 条");
                } catch (Exception e) {
                    System.err.println("[BILIBILI] PGC 索引失败: " + e.getMessage());
                }
            }

            if (updatedToday == null || updatedToday.isEmpty()) {
                return "📭 今天暂时没有" + typeNameOf(contentType) + "更新哦～\n可能B站还没上新，晚点再来看看吧！";
            }

            int totalCount = updatedToday.size();
            int count = resolveDefaultCount(userId, contentType);
            List<String> excluded = history.findExcludedContentIds(userId, contentType);
            List<BilibiliContent> filtered = updatedToday.stream()
                .filter(c -> !excluded.contains(c.getContentId()))
                .limit(count)
                .toList();
            return BilibiliMessageFormatter.formatTodayUpdates(contentType, totalCount, filtered);
        } catch (Exception e) {
            return failure("获取今日更新失败", e);
        }
    }

    private int resolveDefaultCount(String userId, ContentType contentType) {
        try {
            BilibiliPreference p = preferences.getOrCreate(userId, contentType);
            return Math.max(1, p.getRecommendationCount());
        } catch (Exception e) {
            return 3;
        }
    }

    /* ========== 工具方法 ========== */
    public void syncRecommendationSchedule(String userId, ContentType contentType, LocalTime pushTime) {
        if (userId == null || contentType == null || pushTime == null) return;
        try {
            // TODO: 等角色一在 TaskType 枚举里加上 BILIBILI_DAILY_ANIME / BILIBILI_DAILY_MOVIE 后放开
            TaskType taskType = resolveBilibiliTaskType(contentType);
            if (taskType == null) return;

            try {
                List<ScheduledSubscription> all = schedulerService.listByUser(userId);
                if (all != null) {
                    for (ScheduledSubscription s : all) {
                        if (taskType.equals(s.getTaskType())) {
                            try { schedulerService.cancelBySubscriptionId(s.getId(), userId); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}

            String cron = "0 " + pushTime.getMinute() + " " + pushTime.getHour() + " * * ?";
            ScheduledSubscription sub = new ScheduledSubscription();
            sub.setUserId(userId);
            sub.setTaskType(taskType);
            sub.setCronExpression(cron);
            try {
                java.util.Map<String, String> p = new java.util.HashMap<>();
                p.put("bilibili_content_type", contentType.name());
                sub.setParamsJson(objectMapper.writeValueAsString(p));
            } catch (Exception ignored) {}
            sub.setEnabled(true);
            schedulerService.createOrUpdate(sub);
            System.out.println("[BILIBILI-SYNC-SCHEDULE] user=" + userId + " type=" + contentType
                + " cron=" + cron);
        } catch (Exception e) {
            System.err.println("[BILIBILI-SYNC-SCHEDULE] 失败: " + e.getMessage());
        }
    }

    private TaskType resolveBilibiliTaskType(ContentType contentType) {
        final String name;
        if (contentType == ContentType.MOVIE) {
            name = "BILIBILI_DAILY_MOVIE";
        } else if (contentType == ContentType.SERIES) {
            name = "BILIBILI_DAILY_SERIES";
        } else {
            name = "BILIBILI_DAILY_ANIME";
        }
        try {
            return TaskType.valueOf(name);
        } catch (IllegalArgumentException noSuchEnumYet) {
            return null;
        }
    }

    private static String typeNameOf(ContentType t) {
        return switch (t) {
            case BANGUMI -> "动漫";
            case SERIES -> "剧集";
            case MOVIE -> "电影";
            case UPLOADER -> "UP主";
        };
    }

    private String logAndReturn(String tag, Exception e) {
        System.err.println("[BILIBILI-HANDLER] " + tag + ": " + e.getMessage());
        return "❌ " + tag + "：" + (e.getMessage() == null ? "服务暂时不可用，请稍后再试" : e.getMessage());
    }
}
