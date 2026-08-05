package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.PreferenceUpdate;

import java.time.DayOfWeek;
import java.util.Set;

/** 用户每日推荐条件与推送开关的公共业务接口。 */
public interface BilibiliPreferenceService {
    BilibiliPreference getOrCreate(String wechatUserId, ContentType contentType);

    BilibiliPreference update(
        String wechatUserId, ContentType contentType, PreferenceUpdate update);

    BilibiliPreference setPushEnabled(
        String wechatUserId, ContentType contentType, boolean enabled);

    BilibiliPreference setExcludedPushDays(
        String wechatUserId,
        ContentType contentType,
        Set<DayOfWeek> days,
        boolean excluded);

    BilibiliPreference setPreferredTags(
        String wechatUserId, ContentType contentType, Set<String> tags);

    void addTagWeight(
        String wechatUserId, ContentType contentType, Set<String> tags, int weight);
}
