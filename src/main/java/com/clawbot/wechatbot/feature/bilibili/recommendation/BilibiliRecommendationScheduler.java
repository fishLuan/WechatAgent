package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliNotificationPort;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每日高分推荐的定时推送任务。
 *
 * <p>每分钟检查一次各用户的推送时间，到点时生成推荐并通过
 * {@link BilibiliNotificationPort} 通知消息模块发送。</p>
 */
@Component
@ConditionalOnProperty(
    name = "clawbot.bilibili.enabled",
    havingValue = "true"
)
public class BilibiliRecommendationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BilibiliRecommendationScheduler.class);

    private final BilibiliProperties properties;
    private final BilibiliRecommendationService recommendationService;
    private final BilibiliPreferenceServiceImpl preferenceService;
    private BilibiliNotificationPort notificationPort;

    /** 已推送记录：key = userId + ":" + contentType + ":" + date */
    private final Set<String> pushedToday = ConcurrentHashMap.newKeySet();

    public BilibiliRecommendationScheduler(
            BilibiliProperties properties,
            BilibiliRecommendationService recommendationService,
            BilibiliPreferenceServiceImpl preferenceService) {
        this.properties = properties;
        this.recommendationService = recommendationService;
        this.preferenceService = preferenceService;
    }

    /**
     * 通知端口由角色五注入；角色五就位前不依赖该端口。
     */
    @Autowired(required = false)
    public void setNotificationPort(BilibiliNotificationPort notificationPort) {
        this.notificationPort = notificationPort;
    }

    /**
     * 每分钟检查一次，到推送时间的用户触发推荐推送。
     */
    @Scheduled(fixedRate = 60_000)
    void checkAndPush() {
        if (!properties.isEnabled()) return;
        if (notificationPort == null) {
            log.debug("BilibiliNotificationPort 未注册，跳过定时推送");
            return;
        }

        LocalDate today = LocalDate.now();
        String todayKey = today.toString();

        // 对每个内容类型独立检查
        for (ContentType type : List.of(ContentType.BANGUMI, ContentType.SERIES, ContentType.MOVIE)) {
            LocalTime targetTime = properties.pushTime(type);

            // 查找所有开启推送的用户
            List<BilibiliPreference> users = preferenceService.findAllWithPushEnabled(type);
            for (BilibiliPreference pref : users) {
                if (pref.getExcludedPushDays().contains(today.getDayOfWeek())) {
                    continue;
                }
                // 如用户有自定义推送时间，以用户为准
                LocalTime effectiveTime = pref.getPushTime() != null
                    ? pref.getPushTime() : targetTime;
                if (!isWithinMinute(LocalTime.now(), effectiveTime)) continue;

                String pushKey = pref.getWechatUserId() + ":" + type.name() + ":" + todayKey;
                if (pushedToday.contains(pushKey)) continue;

                if (doPush(
                    pref.getWechatUserId(),
                    type,
                    Math.max(1, pref.getRecommendationCount()))) {
                    pushedToday.add(pushKey);
                }
            }
        }
    }

    /**
     * 手动触发今日推荐推送（用于测试或运维）。
     */
    public void pushNow(String wechatUserId, ContentType contentType) {
        BilibiliPreference preference =
            preferenceService.getOrCreate(wechatUserId, contentType);
        doPush(
            wechatUserId,
            contentType,
            Math.max(1, preference.getRecommendationCount()));
    }

    /**
     * 清除今日已推送记录（用于测试）。
     */
    public void resetPushedToday() {
        pushedToday.clear();
    }

    // ---- internal ----

    private boolean doPush(
        String wechatUserId,
        ContentType contentType,
        int recommendationCount
    ) {
        if (notificationPort == null) {
            log.warn("BilibiliNotificationPort 未注册，无法推送");
            return false;
        }
        try {
            RecommendationResult result = recommendationService.recommend(
                wechatUserId, contentType,
                recommendationCount);

            if (result.items().isEmpty()) {
                log.info("用户 {} {} 今日无合适推荐", wechatUserId, contentType);
                return true;
            }

            notificationPort.notifyDailyRecommendation(wechatUserId, result);
            log.info("已推送 {} 推荐给用户 {}（{} 部）",
                contentType, wechatUserId, result.items().size());
            return true;
        } catch (Exception e) {
            log.error("推送 {} 推荐给用户 {} 失败: {}",
                contentType, wechatUserId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 判断当前时间是否与目标时间在同一分钟内。
     */
    private static boolean isWithinMinute(LocalTime now, LocalTime target) {
        if (target == null) return false;
        long diffSeconds = Math.abs(now.toSecondOfDay() - target.toSecondOfDay());
        return diffSeconds < 60;
    }
}
