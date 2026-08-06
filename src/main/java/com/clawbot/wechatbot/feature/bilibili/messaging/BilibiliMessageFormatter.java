package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.CheckResult;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将领域对象转换为适合微信阅读的纯文本。 */
public final class BilibiliMessageFormatter {
    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public BilibiliMessageFormatter() {
    }

    public static String formatRecommendation(RecommendationResult result) {
        String type = typeName(result.contentType());
        if (result.items().isEmpty()) {
            return "暂时没有找到符合条件的" + type + "，可以降低最低评分后再试。";
        }
        StringBuilder out = new StringBuilder("今日B站")
            .append(type).append("推荐\n\n");
        int index = 1;
        for (RecommendedContent item : result.items()) {
            out.append(index).append(". ").append(item.title());
            if (item.rating() != null) {
                out.append("  ⭐").append(
                    String.format(Locale.ROOT, "%.1f", item.rating()));
            }
            out.append('\n');
            appendLine(out, "更新", item.latestEpisodeTitle());
            appendLine(out, "理由", item.recommendationReason());
            appendLine(out, "链接", item.pageUrl());
            if (item.contentType() == ContentType.MOVIE) {
                out.append("回复“想看").append(index)
                    .append("”或“看过").append(index).append("”\n\n");
            } else {
                out.append("回复“订阅").append(index)
                    .append("”可追更，回复“看过").append(index).append("”可标记\n\n");
            }
            index++;
        }
        return out.toString().trim();
    }

    public static String formatSubscription(SubscriptionResult result) {
        if (result == null) return "❌ 订阅失败：服务未返回结果";
        String prefix = result.success() ? "✅ " : "❌ ";
        if (result.alreadySubscribed()) prefix = "ℹ️ ";
        String message = nonBlank(result.message(), result.success() ? "订阅成功" : "订阅失败");
        if (result.title() == null || message.contains(result.title())) {
            return prefix + message;
        }
        return prefix + message + "：" + result.title();
    }

    public static String formatSubscriptions(List<SubscriptionView> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return "你还没有订阅任何B站作品。";
        }
        StringBuilder out = new StringBuilder("我的B站订阅\n\n");
        int index = 1;
        for (SubscriptionView item : subscriptions) {
            out.append(index++).append(". ").append(item.title())
                .append("（").append(item.status()).append("）");
            if (item.latestEpisodeNumber() != null) {
                out.append(" · 第").append(item.latestEpisodeNumber()).append("集");
            }
            out.append('\n');
        }
        out.append("\n可以回复“取消订阅2”“暂停订阅1”或“恢复订阅1”。");
        return out.toString();
    }

    public static String formatCheckResult(CheckResult result) {
        if (result == null) return "检查更新失败：服务未返回结果。";
        if (result.updates().isEmpty()) {
            return "已检查 " + result.checkedCount() + " 个订阅，暂时没有新内容。";
        }
        StringBuilder out = new StringBuilder("检查完成，发现 ")
            .append(result.updateCount()).append(" 个更新：\n\n");
        for (EpisodeUpdateNotification update : result.updates()) {
            out.append(formatEpisodeUpdate(update)).append("\n\n");
        }
        return out.toString().trim();
    }

    public static String formatEpisodeUpdate(EpisodeUpdateNotification update) {
        StringBuilder out = new StringBuilder("《")
            .append(update.title()).append("》更新了");
        if (update.episodeNumber() != null) {
            out.append("：第").append(update.episodeNumber()).append("集");
        } else if (update.episodeTitle() != null && !update.episodeTitle().isBlank()) {
            out.append("：").append(update.episodeTitle().trim());
        }
        if (update.episodeUrl() != null && !update.episodeUrl().isBlank()) {
            out.append("\n").append(update.episodeUrl().trim());
        }
        return out.toString();
    }

    public static String formatSearchResults(String query, List<BilibiliContent> results) {
        if (results == null || results.isEmpty()) {
            return "没有在B站找到与“" + query + "”相关的作品。";
        }
        StringBuilder out = new StringBuilder("找到以下相关作品：\n\n");
        int index = 1;
        for (BilibiliContent item : results) {
            out.append(index++).append(". ").append(item.getTitle());
            if (item.getRating() != null) out.append(" ⭐").append(item.getRating());
            if (item.isFinished()) out.append("（已完结）");
            if (item.getPageUrl() != null && !item.getPageUrl().isBlank()) {
                out.append("\n").append(item.getPageUrl());
            }
            out.append("\n\n");
        }
        return out.toString().trim();
    }

    public static String formatPreference(BilibiliPreference preference) {
        Set<String> tags = preference.getPreferredTags();
        String tagStr = tags.isEmpty() ? "（未设置）" : String.join("、", tags);
        return typeName(preference.getContentType()) + "推荐设置："
            + "\n偏好标签：" + tagStr
            + "\n推送：" + (preference.isPushEnabled() ? "已开启" : "已关闭")
            + "\n时间：" + preference.getPushTime()
            + "\n不推送日期：" + formatExcludedDays(preference)
            + "\n最低评分：" + preference.getMinimumRating()
            + "\n数量：" + preference.getRecommendationCount();
    }

    public static String formatPreferenceUpdated(
        String typeName, String fieldName, String value
    ) {
        return "✅ 已更新" + typeName + fieldName + "：" + value;
    }

    public static String formatResolveFailure(String reason) {
        return "❌ 无法识别这个B站作品：" + nonBlank(reason, "请确认链接或作品名");
    }

    private static void appendLine(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append("：").append(value.trim()).append('\n');
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static String typeName(ContentType type) {
        return switch (type) {
            case BANGUMI -> "动漫";
            case SERIES -> "剧集";
            case MOVIE -> "电影";
            case UPLOADER -> "UP主内容";
        };
    }

    public static String formatTodayUpdates(ContentType contentType, int totalUpdated,
                                             List<BilibiliContent> items) {
        return formatUpdates(contentType, "今天", totalUpdated, items);
    }

    public static String formatUpdates(ContentType contentType, String rangeName,
                                       int totalUpdated, List<BilibiliContent> items) {
        String typeNameStr = typeName(contentType);
        String icon = switch (contentType) {
            case BANGUMI -> "🎐";
            case SERIES -> "📺";
            case MOVIE -> "🎥";
            case UPLOADER -> "🧑‍💻";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("📺 **B站").append(rangeName).append(typeNameStr).append("更新** 📺\n\n");
        sb.append(rangeName).append("共有 ").append(totalUpdated).append(" 部")
            .append(typeNameStr).append("更新");
        if (items == null || items.isEmpty()) {
            sb.append("，但没有符合你偏好的～试试降低最低评分？");
            return sb.toString();
        }
        sb.append("，为你推荐以下 ").append(items.size()).append(" 部：\n\n");
        int no = 1;
        for (BilibiliContent c : items) {
            sb.append(no).append(". ").append(icon).append(" **").append(c.getTitle()).append("**");
            if (c.getRating() != null && c.getRating() > 0) {
                sb.append("  ⭐").append(String.format("%.1f", c.getRating()));
            }
            sb.append("\n");
            if (c.getGenres() != null && !c.getGenres().isEmpty()) {
                sb.append("   🏷️ ").append(String.join(" · ", c.getGenres())).append("\n");
            }
            if (c.getLatestEpisodeNumber() != null) {
                sb.append("   🔥 第 ").append(c.getLatestEpisodeNumber()).append(" 话");
                if (c.getLatestEpisodeTitle() != null && !c.getLatestEpisodeTitle().isBlank()) {
                    String epTitle = c.getLatestEpisodeTitle();
                    // 去掉 "第X话" 前缀
                    epTitle = epTitle.replaceAll("^第\\d+话\\s*", "");
                    // 去掉 "更新至第X话" 这类描述性前缀
                    epTitle = epTitle.replaceAll("^更新至第\\d+话\\s*", "");
                    if (!epTitle.isBlank() && !epTitle.matches("^[\\d\\s]+$")) {
                        sb.append("「").append(epTitle).append("」");
                    }
                }
                sb.append("\n");
            } else if (c.getLatestEpisodeTitle() != null && !c.getLatestEpisodeTitle().isBlank()) {
                sb.append("   🔥 ").append(c.getLatestEpisodeTitle()).append("\n");
            }
            if (c.getLatestEpisodePubTime() != null) {
                sb.append("   ⏰ ").append(
                    DTF.format(c.getLatestEpisodePubTime().atZone(BJ))).append("\n");
            } else {
                sb.append("   📡 连载中\n");
            }
            if (c.getPageUrl() != null && !c.getPageUrl().isBlank()) {
                sb.append("   🔗 ").append(c.getPageUrl()).append("\n");
            }
            if (contentType.isEpisodeTrackable()) {
                sb.append("   ➡️ 回复 **订阅").append(no).append("** 追更\n\n");
            } else {
                sb.append("\n");
            }
            no++;
        }
        sb.append("💡 想看更多？回复「今日动漫推荐」获取高分推荐～");
        return sb.toString();
    }

    private static String formatExcludedDays(BilibiliPreference preference) {
        if (preference.getExcludedPushDays().isEmpty()) return "无";
        return preference.getExcludedPushDays().stream()
            .sorted()
            .map(BilibiliMessageFormatter::dayName)
            .reduce((left, right) -> left + "、" + right)
            .orElse("无");
    }

    private static String dayName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
