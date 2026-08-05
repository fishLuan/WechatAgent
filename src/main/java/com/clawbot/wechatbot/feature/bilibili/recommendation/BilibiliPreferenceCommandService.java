package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandParser;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliMessageFormatter;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.PreferenceUpdate;
import com.clawbot.wechatbot.feature.bilibili.scheduling.BilibiliSchedulePort;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 推荐偏好与定时任务用例，避免消息路由器直接编排调度基础设施。 */
@Component
public final class BilibiliPreferenceCommandService {
    private static final DateTimeFormatter HH_MM =
        DateTimeFormatter.ofPattern("HH:mm");

    private final BilibiliPreferenceService preferences;
    private final BilibiliSchedulePort schedules;

    public BilibiliPreferenceCommandService(
        BilibiliPreferenceService preferences, BilibiliSchedulePort schedules
    ) {
        this.preferences = preferences;
        this.schedules = schedules;
    }

    public String updateField(
        String userId, ContentType type, String key, String value
    ) {
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
                if (rating < 0 || rating > 10) return "❌ 最低评分必须在 0 到 10 之间。";
                field = "最低评分";
                display = rating + " 分";
            }
            case "count" -> {
                count = Integer.parseInt(value);
                if (count < 1 || count > 10) return "❌ 推荐数量必须在 1 到 10 之间。";
                field = "推荐数量";
                display = count + " 部";
            }
            case "tags" -> {
                Set<String> currentTags = new LinkedHashSet<>(
                    current.getPreferredTags());
                boolean wasCleared = value.isBlank();
                if (wasCleared) {
                    currentTags.clear();
                } else {
                    Set.of(value.split("[,，\\s]+")).forEach(currentTags::add);
                }
                preferences.setPreferredTags(userId, actualType, currentTags);
                if (wasCleared) {
                    return "✅ " + BilibiliMessageFormatter.typeName(actualType)
                        + "偏好标签已清空。";
                }
                return "✅ " + BilibiliMessageFormatter.typeName(actualType)
                    + "偏好标签：" + String.join("、", currentTags);
            }
            case "remove_tag" -> {
                Set<String> currentTags = new LinkedHashSet<>(
                    current.getPreferredTags());
                Set<String> toRemove = value.isBlank() ? Set.of()
                    : Set.of(value.split("[,，\\s]+"));
                currentTags.removeAll(toRemove);
                preferences.setPreferredTags(userId, actualType, currentTags);
                return "✅ 已移除标签：" + String.join("、", toRemove)
                    + "，当前：" + (currentTags.isEmpty() ? "（无）" : String.join("、", currentTags));
            }
            default -> {
                return "❌ 未知设置项：" + key;
            }
        }
        BilibiliPreference saved = preferences.update(
            userId, actualType,
            new PreferenceUpdate(
                rating, count, pushTime, genres(current), current.isPushEnabled()));
        if ("push_time".equals(key)) {
            schedules.scheduleDaily(
                userId, actualType, Math.max(1, saved.getRecommendationCount()), pushTime);
        }
        return BilibiliMessageFormatter.formatPreferenceUpdated(
            BilibiliMessageFormatter.typeName(actualType), field, display);
    }

    public String toggle(String userId, ContentType type, boolean enabled) {
        ContentType actualType = type == null ? ContentType.BANGUMI : type;
        preferences.setPushEnabled(userId, actualType, enabled);
        return "✅ " + BilibiliMessageFormatter.typeName(actualType)
            + "每日推送已" + (enabled ? "开启" : "关闭") + "。";
    }

    public String updateWeekdays(
        String userId, ContentType type, Set<DayOfWeek> days, boolean excluded
    ) {
        if (days == null || days.isEmpty()) return "❌ 请指定需要设置的星期。";
        List<ContentType> types = type == null
            ? List.of(ContentType.BANGUMI, ContentType.SERIES, ContentType.MOVIE)
            : List.of(type);
        for (ContentType actualType : types) {
            preferences.setExcludedPushDays(userId, actualType, days, excluded);
        }
        String target = type == null
            ? "动漫、剧集和电影" : BilibiliMessageFormatter.typeName(type);
        return "✅ 已设置" + target + "在" + formatDays(days)
            + (excluded ? "不发送每日推荐。" : "恢复每日推荐。");
    }

    public String configureDaily(
        String userId,
        BilibiliCommandParser.ParsedCommand command,
        String originalInput
    ) {
        List<ContentType> types = contentTypes(originalInput, command.contentType());
        if ("ONCE".equals(command.state())) {
            return scheduleOnce(userId, command, types);
        }
        LocalTime time = LocalTime.parse(command.fieldValue(), HH_MM);
        List<BilibiliPreference> saved = new ArrayList<>();
        for (ContentType type : types) {
            BilibiliPreference current = preferences.getOrCreate(userId, type);
            double rating = command.minimumRating() == null
                ? current.getMinimumRating() : command.minimumRating();
            int count = command.recommendationCount() == null
                ? current.getRecommendationCount() : command.recommendationCount();
            if (rating < 0 || rating > 10 || count < 1 || count > 10) {
                return "❌ 推送条件不合法：评分需为0～10，数量需为1～10。";
            }
            BilibiliPreference value = preferences.update(
                userId, type,
                new PreferenceUpdate(rating, count, time, genres(current), true));
            saved.add(value);
            schedules.scheduleDaily(userId, type, count, time);
            applyWeeklyPolicy(userId, type, command);
        }
        return formatDailyResult(saved, command, time);
    }

    public String show(String userId) {
        StringBuilder out = new StringBuilder("B站推荐设置\n\n");
        for (ContentType type : ContentType.values()) {
            out.append(BilibiliMessageFormatter.formatPreference(
                preferences.getOrCreate(userId, type))).append("\n\n");
        }
        return out.toString().trim();
    }

    public void syncDaily(
        String userId, ContentType type, LocalTime pushTime
    ) {
        if (userId == null || type == null || pushTime == null) return;
        BilibiliPreference preference = preferences.getOrCreate(userId, type);
        schedules.scheduleDaily(
            userId, type, Math.max(1, preference.getRecommendationCount()), pushTime);
    }

    private String scheduleOnce(
        String userId,
        BilibiliCommandParser.ParsedCommand command,
        List<ContentType> types
    ) {
        long fireAt = Long.parseLong(command.fieldValue());
        if (fireAt <= System.currentTimeMillis()) return "❌ 推送时间必须晚于当前时间。";
        for (ContentType type : types) {
            BilibiliPreference preference = preferences.getOrCreate(userId, type);
            int count = command.recommendationCount() == null
                ? Math.max(1, preference.getRecommendationCount())
                : command.recommendationCount();
            schedules.scheduleOneTime(userId, type, count, Instant.ofEpochMilli(fireAt));
        }
        String time = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fireAt), ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String names = types.stream().map(BilibiliMessageFormatter::typeName)
            .reduce((left, right) -> left + "和" + right).orElse("内容");
        return "✅ 已设置一次性任务：" + time + " 推送" + names + "。";
    }

    private void applyWeeklyPolicy(
        String userId,
        ContentType type,
        BilibiliCommandParser.ParsedCommand command
    ) {
        if (!"WEEKLY".equals(command.state())) return;
        Set<DayOfWeek> included = parseDays(command.fieldName());
        Set<DayOfWeek> excluded = new LinkedHashSet<>(Set.of(DayOfWeek.values()));
        excluded.removeAll(included);
        preferences.setExcludedPushDays(
            userId, type, Set.of(DayOfWeek.values()), false);
        preferences.setExcludedPushDays(userId, type, excluded, true);
    }

    private String formatDailyResult(
        List<BilibiliPreference> saved,
        BilibiliCommandParser.ParsedCommand command,
        LocalTime time
    ) {
        if (saved.size() == 1) {
            BilibiliPreference value = saved.getFirst();
            String frequency = "WEEKLY".equals(command.state())
                ? "每" + formatDays(parseDays(command.fieldName())) + " " : "每天 ";
            return "✅ 已设置" + frequency + value.getPushTime().format(HH_MM)
                + " 推送 " + value.getRecommendationCount() + " 部高分"
                + BilibiliMessageFormatter.typeName(value.getContentType())
                + "（最低 " + value.getMinimumRating() + " 分）。";
        }
        String prefix = "WEEKLY".equals(command.state())
            ? "✅ 已设置每" + formatDays(parseDays(command.fieldName())) + " "
            : "✅ 已设置每天 ";
        StringBuilder reply = new StringBuilder(prefix)
            .append(time.format(HH_MM)).append(" 推送：");
        for (BilibiliPreference value : saved) {
            reply.append("\n- ").append(value.getRecommendationCount())
                .append(" 部高分")
                .append(BilibiliMessageFormatter.typeName(value.getContentType()))
                .append("（最低 ").append(value.getMinimumRating()).append(" 分）");
        }
        return reply.toString();
    }

    private List<ContentType> contentTypes(String input, ContentType fallback) {
        String text = input == null ? "" : input;
        LinkedHashSet<ContentType> types = new LinkedHashSet<>();
        if (text.contains("动漫") || text.contains("番剧")) types.add(ContentType.BANGUMI);
        if (text.contains("电视剧") || text.contains("剧集")
            || text.contains("美剧") || text.contains("日剧")
            || text.contains("韩剧") || text.contains("国产剧")) {
            types.add(ContentType.SERIES);
        }
        if (text.contains("电影")) types.add(ContentType.MOVIE);
        if (types.isEmpty() && fallback != null) types.add(fallback);
        return List.copyOf(types);
    }

    private Set<String> genres(BilibiliPreference preference) {
        return preference.getPreferredGenres() == null
            ? Set.of() : preference.getPreferredGenres();
    }

    private Set<DayOfWeek> parseDays(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<DayOfWeek> days = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::trim)
            .filter(day -> !day.isEmpty()).map(DayOfWeek::valueOf).forEach(days::add);
        return days;
    }

    private String formatDays(Set<DayOfWeek> days) {
        return days.stream().sorted().map(day -> switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        }).reduce((left, right) -> left + "、" + right).orElse("");
    }
}
