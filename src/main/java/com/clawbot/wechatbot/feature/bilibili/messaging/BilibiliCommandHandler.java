package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.CheckResult;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.PreferenceUpdate;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class BilibiliCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(BilibiliCommandHandler.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern HHMM_REGEX = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

    private final BilibiliSubscriptionService subscriptionService;
    private final BilibiliRecommendationService recommendationService;
    private final BilibiliPreferenceService preferenceService;
    private final SchedulerControlService schedulerService;
    private final WeChatSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public BilibiliCommandHandler(
        @Lazy BilibiliSubscriptionService subscriptionService,
        @Lazy BilibiliRecommendationService recommendationService,
        @Lazy BilibiliPreferenceService preferenceService,
        @Lazy SchedulerControlService schedulerService,
        WeChatSessionRegistry sessionRegistry,
        ObjectMapper objectMapper
    ) {
        this.subscriptionService = subscriptionService;
        this.recommendationService = recommendationService;
        this.preferenceService = preferenceService;
        this.schedulerService = schedulerService;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    /* ========== 入口：统一字符串处理（Handler 直接调这个） ========== */
    public String handle(String userId, String text) {
        sessionRegistry.markActive(userId);
        BilibiliCommandParser.ParsedCommand cmd = BilibiliCommandParser.parse(text);
        return switch (cmd.type()) {
            case TODAY_RECOMMEND_ANIME -> handleTodayRecommend(userId, ContentType.BANGUMI);
            case TODAY_RECOMMEND_MOVIE -> handleTodayRecommend(userId, ContentType.MOVIE);
            case TODAY_RECOMMEND_SERIES -> handleTodayRecommend(userId, ContentType.SERIES);
            case SUBSCRIBE_BY_INDEX -> handleSubscribeByIndex(userId, cmd.index(), cmd.contentType());
            case MARK_WANT_TO_WATCH -> handleMarkState(userId, cmd.index(), "want_to_watch");
            case MARK_WATCHED -> handleMarkState(userId, cmd.index(), "watched");
            case MARK_DISLIKED -> handleMarkState(userId, cmd.index(), "disliked");
            case SUBSCRIBE_BY_URL -> handleSubscribeByUrl(userId, cmd.url());
            case LIST_SUBSCRIPTIONS -> handleListSubscriptions(userId);
            case CANCEL_SUBSCRIPTION -> handleCancel(userId, cmd.index(), cmd.subscriptionId());
            case PAUSE_SUBSCRIPTION -> handlePause(userId, cmd.index(), cmd.subscriptionId());
            case RESUME_SUBSCRIPTION -> handleResume(userId, cmd.index(), cmd.subscriptionId());
            case SET_PUSH_TIME -> handleSetPreference(userId, cmd.contentType(), "push_time", cmd.fieldValue());
            case SET_MIN_RATING -> handleSetPreference(userId, cmd.contentType(), "min_rating", cmd.fieldValue());
            case SET_RECOMMEND_COUNT -> handleSetPreference(userId, cmd.contentType(), "count", cmd.fieldValue());
            case TOGGLE_PUSH -> handleTogglePush(userId, cmd.contentType(), cmd.pushEnabled());
            case SHOW_PREFERENCES -> handleShowPreference(userId);
            case CHECK_UPDATES_NOW -> handleCheckUpdatesNow(userId);
            case UNKNOWN -> "【UNHANDLED-BILIBILI-UNKNOWN】";  // 交给 AI
        };
    }

    /* ========== 1. 订阅 ========== */
    public String handleSubscribeByUrl(String userId, String url) {
        sessionRegistry.markActive(userId);
        if (url == null || url.isBlank()) {
            return "❌ 没有识别到有效的B站链接～";
        }
        try {
            SubscriptionResult r = subscriptionService.subscribeByUrl(userId, url.trim());
            return BilibiliMessageFormatter.formatSubscriptionResult(r);
        } catch (Exception e) {
            return logAndReturn("订阅失败", e);
        }
    }

    public String handleSubscribeByIndex(String userId, Integer index, ContentType contentType) {
        sessionRegistry.markActive(userId);
        if (index == null || index < 1) {
            return "❌ 要订阅的编号不对哦～正确格式：订阅 1～9 之间的数字，比如「订阅2」";
        }
        try {
            RecommendedContent item = recommendationService.findPendingItem(userId, index);
            if (item == null) {
                return "❌ 找不到第 " + index + " 部推荐作品，可能已经过了推荐时间？\n先回复「今日动漫推荐」获取最新列表哦～";
            }
            log.info("订阅条目: title={}, contentId={}, seasonId={}, pageUrl={}",
                item.title(), item.contentId(), item.seasonId(), item.pageUrl());
            if (ContentType.MOVIE.equals(item.contentType())) {
                return BilibiliMessageFormatter.formatMovieSubscriptionRejected(item.title());
            }
            SubscriptionResult r = subscriptionService.subscribeByContentId(
                userId, item.contentType(), item.seasonId());
            return BilibiliMessageFormatter.formatSubscriptionResult(r);
        } catch (Exception e) {
            return logAndReturn("订阅失败", e);
        }
    }

    /* ========== 2. 查看订阅列表 ========== */
    public String handleListSubscriptions(String userId) {
        sessionRegistry.markActive(userId);
        try {
            List<SubscriptionView> list = subscriptionService.listSubscriptions(userId);
            return BilibiliMessageFormatter.formatSubscriptionList(list);
        } catch (Exception e) {
            return logAndReturn("查看订阅失败", e);
        }
    }

    /* ========== 3. 取消/暂停/恢复 ========== */
    public String handleCancel(String userId, Integer index, String subscriptionId) {
        sessionRegistry.markActive(userId);
        return doCancelPauseResume(userId, index, subscriptionId, "cancel");
    }

    public String handlePause(String userId, Integer index, String subscriptionId) {
        sessionRegistry.markActive(userId);
        return doCancelPauseResume(userId, index, subscriptionId, "pause");
    }

    public String handleResume(String userId, Integer index, String subscriptionId) {
        sessionRegistry.markActive(userId);
        return doCancelPauseResume(userId, index, subscriptionId, "resume");
    }

    private String doCancelPauseResume(String userId, Integer index, String subscriptionId, String op) {
        try {
            String subId = resolveSubscriptionId(userId, index, subscriptionId);
            if (subId == null) {
                return "❌ 没有找到对应的订阅～回复「我的订阅」查看所有订阅的编号和ID哦";
            }
            OperationResult r = switch (op) {
                case "pause" -> subscriptionService.pause(userId, subId);
                case "resume" -> subscriptionService.resume(userId, subId);
                default -> subscriptionService.cancel(userId, subId);
            };
            return BilibiliMessageFormatter.formatOperationResult(r);
        } catch (Exception e) {
            return logAndReturn("操作失败", e);
        }
    }

    private String resolveSubscriptionId(String userId, Integer index, String subscriptionId) {
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return subscriptionId.trim();
        }
        if (index != null && index > 0) {
            try {
                List<SubscriptionView> list = subscriptionService.listSubscriptions(userId);
                if (list != null && index <= list.size()) {
                    return list.get(index - 1).subscriptionId();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /* ========== 4. 今日推荐 ========== */
    public String handleTodayRecommend(String userId, ContentType contentType) {
        sessionRegistry.markActive(userId);
        if (contentType == null) contentType = ContentType.BANGUMI;
        try {
            int count = resolveDefaultCount(userId, contentType);
            RecommendationResult r = recommendationService.recommend(userId, contentType, count);
            return BilibiliMessageFormatter.formatRecommendation(r);
        } catch (Exception e) {
            return logAndReturn("获取推荐失败", e);
        }
    }

    private int resolveDefaultCount(String userId, ContentType contentType) {
        try {
            BilibiliPreference p = preferenceService.getOrCreate(userId, contentType);
            return Math.max(1, p.getRecommendationCount());
        } catch (Exception e) {
            return 3;
        }
    }

    /* ========== 5. 标记状态（想看/看过/不喜欢） ========== */
    public String handleMarkState(String userId, Integer index, String actionStr) {
        sessionRegistry.markActive(userId);
        if (index == null || index < 1) {
            return "❌ 要标记的编号不对哦～正确格式：想看2 / 看过3 / 不喜欢1";
        }
        try {
            RecommendedContent item = recommendationService.findPendingItem(userId, index);
            if (item == null) {
                return "❌ 找不到第 " + index + " 部推荐作品～先回复「今日动漫推荐」获取最新推荐吧";
            }
            String stateDesc = switch (actionStr) {
                case "want_to_watch" -> "标记想看";
                case "watched" -> {
                    recommendationService.markWatched(userId, index);
                    yield "标记看过";
                }
                case "disliked" -> {
                    recommendationService.markDisliked(userId, index);
                    yield "标记不喜欢";
                }
                default -> "标记";
            };
            return BilibiliMessageFormatter.formatMarkResult(index, item.contentType(), stateDesc, item.title());
        } catch (Exception e) {
            return logAndReturn("标记失败", e);
        }
    }

    /* ========== 6. 偏好设置 ========== */
    public String handleSetPreference(String userId, ContentType contentType, String key, String valueStr) {
        sessionRegistry.markActive(userId);
        if (contentType == null) contentType = ContentType.BANGUMI;
        String typeName = typeNameOf(contentType);
        try {
            BilibiliPreference current = preferenceService.getOrCreate(userId, contentType);
            double curRating = current.getMinimumRating();
            int curCount = current.getRecommendationCount();
            LocalTime curTime = current.getPushTime() == null ? LocalTime.of(20, 0) : current.getPushTime();
            Set<String> curGenres = current.getPreferredGenres() == null ? Set.of() : current.getPreferredGenres();
            boolean curEnabled = current.isPushEnabled();

            double newRating = curRating;
            int newCount = curCount;
            LocalTime newTime = curTime;
            Set<String> newGenres = curGenres;
            boolean newEnabled = curEnabled;

            String fieldName;
            String valueForReply;
            switch (key) {
                case "push_time" -> {
                    if (valueStr == null || !HHMM_REGEX.matcher(valueStr.trim()).matches()) {
                        return "❌ 时间格式不对哦～正确格式 HH:mm，比如「20:00」「21:30」";
                    }
                    newTime = LocalTime.parse(valueStr.trim(), HHMM);
                    fieldName = "每日推送时间";
                    valueForReply = newTime.format(HHMM);
                }
                case "min_rating" -> {
                    newRating = Double.parseDouble(valueStr);
                    if (newRating < 0 || newRating > 10) {
                        return "❌ 评分必须在 0～10 之间哦～";
                    }
                    fieldName = "最低评分";
                    valueForReply = newRating + " 分";
                }
                case "count" -> {
                    newCount = Integer.parseInt(valueStr);
                    if (newCount < 1 || newCount > 10) {
                        return "❌ 推荐数量必须在 1～10 之间哦～";
                    }
                    fieldName = "推荐数量";
                    valueForReply = "每次 " + newCount + " 部";
                }
                default -> { return "❌ 未知的设置项：" + key; }
            }

            PreferenceUpdate update = new PreferenceUpdate(newRating, newCount, newTime, newGenres, newEnabled);
            preferenceService.update(userId, contentType, update);
            if ("push_time".equals(key)) {
                try {
                    syncRecommendationSchedule(userId, contentType, newTime);
                } catch (Exception ignoreScheduler) {}
            }
            return BilibiliMessageFormatter.formatPreferenceUpdated(typeName, fieldName, valueForReply);
        } catch (NumberFormatException ne) {
            return "❌ 数值格式不对：" + valueStr;
        } catch (Exception e) {
            return logAndReturn("设置失败", e);
        }
    }

    public String handleTogglePush(String userId, ContentType contentType, Boolean enabled) {
        sessionRegistry.markActive(userId);
        if (contentType == null) contentType = ContentType.BANGUMI;
        try {
            boolean e = Boolean.TRUE.equals(enabled);
            preferenceService.setPushEnabled(userId, contentType, e);
            String action = e ? "开启" : "关闭";
            return "✅ 已" + action + "【" + typeNameOf(contentType) + "】的每日推送！";
        } catch (Exception e) {
            return logAndReturn("切换推送开关失败", e);
        }
    }

    public String handleShowPreference(String userId) {
        sessionRegistry.markActive(userId);
        try {
            BilibiliPreference anime = null;
            BilibiliPreference series = null;
            BilibiliPreference movie = null;
            try { anime = preferenceService.getOrCreate(userId, ContentType.BANGUMI); } catch (Exception ignored) {}
            try { series = preferenceService.getOrCreate(userId, ContentType.SERIES); } catch (Exception ignored) {}
            try { movie = preferenceService.getOrCreate(userId, ContentType.MOVIE); } catch (Exception ignored) {}
            return BilibiliMessageFormatter.formatPreference(anime, series, movie);
        } catch (Exception e) {
            return logAndReturn("获取设置失败", e);
        }
    }

    /* ========== 7. 立即检查更新 ========== */
    public String handleCheckUpdatesNow(String userId) {
        sessionRegistry.markActive(userId);
        try {
            CheckResult r = subscriptionService.checkNow(userId);
            return BilibiliMessageFormatter.formatCheckResult(
                r.checkedCount(), r.updateCount(), r.updates());
        } catch (Exception e) {
            return logAndReturn("检查更新失败", e);
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