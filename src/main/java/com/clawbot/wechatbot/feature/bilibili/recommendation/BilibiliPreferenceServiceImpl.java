package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.PreferenceUpdate;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliPreferenceRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * B 站推荐偏好管理实现。
 *
 * <p>处理用户对动漫、剧集和电影的推荐偏好（最低评分、推荐数量、题材偏好、推送时间等）。
 * 用户未主动设置时使用 {@link BilibiliProperties} 中的全局默认值。</p>
 */
@Service
public class BilibiliPreferenceServiceImpl implements BilibiliPreferenceService {

    private final BilibiliPreferenceRepository repository;
    private final BilibiliProperties properties;

    public BilibiliPreferenceServiceImpl(
            BilibiliPreferenceRepository repository,
            BilibiliProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public BilibiliPreference getOrCreate(String wechatUserId, ContentType contentType) {
        return repository.findByWechatUserIdAndContentType(wechatUserId, contentType)
            .orElseGet(() -> createDefault(wechatUserId, contentType));
    }

    @Override
    public BilibiliPreference update(
            String wechatUserId, ContentType contentType, PreferenceUpdate update) {
        BilibiliPreference pref = getOrCreate(wechatUserId, contentType);
        pref.setMinimumRating(update.minimumRating());
        pref.setRecommendationCount(update.recommendationCount());
        pref.setPushTime(update.pushTime());
        pref.setPreferredGenres(new LinkedHashSet<>(update.preferredGenres()));
        pref.setPushEnabled(update.pushEnabled());
        pref.setUpdatedAt(Instant.now());
        return repository.save(pref);
    }

    @Override
    public BilibiliPreference setPushEnabled(
            String wechatUserId, ContentType contentType, boolean enabled) {
        BilibiliPreference pref = getOrCreate(wechatUserId, contentType);
        pref.setPushEnabled(enabled);
        pref.setUpdatedAt(Instant.now());
        return repository.save(pref);
    }

    @Override
    public BilibiliPreference setExcludedPushDays(
        String wechatUserId,
        ContentType contentType,
        Set<DayOfWeek> days,
        boolean excluded
    ) {
        BilibiliPreference pref = getOrCreate(wechatUserId, contentType);
        Set<DayOfWeek> updated = new LinkedHashSet<>(pref.getExcludedPushDays());
        if (excluded) {
            updated.addAll(days == null ? Set.of() : days);
        } else {
            updated.removeAll(days == null ? Set.of() : days);
        }
        pref.setExcludedPushDays(updated);
        pref.setUpdatedAt(Instant.now());
        return repository.save(pref);
    }

    /**
     * 查询所有启用推送的用户偏好列表（按内容类型）。
     */
    public java.util.List<BilibiliPreference> findAllWithPushEnabled(ContentType contentType) {
        return repository.findByContentTypeAndPushEnabledTrue(contentType);
    }

    // ---- internal ----

    private BilibiliPreference createDefault(String wechatUserId, ContentType contentType) {
        BilibiliPreference pref = new BilibiliPreference(wechatUserId, contentType);
        pref.setMinimumRating(properties.minimumRating(contentType));
        pref.setRecommendationCount(properties.recommendationCount(contentType));
        pref.setPushTime(properties.pushTime(contentType));
        pref.setPushEnabled(true);
        return repository.save(pref);
    }
}
