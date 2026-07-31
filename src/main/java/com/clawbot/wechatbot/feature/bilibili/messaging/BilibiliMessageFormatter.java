package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.EpisodeUpdateNotification;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

public class BilibiliMessageFormatter {
    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public BilibiliMessageFormatter() {}

    public static String formatEpisodeUpdate(EpisodeUpdateNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("📺 **B站追更提醒** 📺\n\n");
        sb.append("🎬 ").append(n.title()).append("\n");
        sb.append("🔥 第 ").append(n.episodeNumber()).append("话：")
          .append(n.episodeTitle() == null ? "新集上线啦！" : n.episodeTitle()).append("\n");
        if (n.episodeUrl() != null && !n.episodeUrl().isBlank()) {
            sb.append("🔗 观看：").append(n.episodeUrl()).append("\n");
        }
        sb.append("⏰ 更新于：").append(
            n.detectedAt() == null ? "刚刚" :
            DTF.format(n.detectedAt().atZone(BJ))).append("\n");
        sb.append("\n快去B站追番吧～B站链接直接点击就能打开哦！");
        return sb.toString();
    }

    public static String formatRecommendation(RecommendationResult r) {
        if (r == null || r.items() == null || r.items().isEmpty()) {
            return "📭 今天暂时没有符合条件的推荐哦～\n你可以降低最低评分或试试其他类型！";
        }
        ContentType type = r.contentType();
        String typeName = typeNameOf(type);
        String icon = iconOf(type);

        StringBuilder sb = new StringBuilder();
        sb.append("🎯 **今日B站").append(typeName).append("推荐** 🎯\n\n");
        sb.append("共找到 ").append(r.items().size()).append(" 部符合条件的作品：\n\n");

        int no = 1;
        for (RecommendedContent item : r.items()) {
            sb.append("─── 第 ").append(no).append(" 部 ───\n");
            sb.append(icon).append(" **").append(item.title()).append("**");
            if (item.rating() != null && item.rating() > 0) {
                sb.append("  ⭐").append(String.format("%.1f", item.rating()));
            }
            sb.append("\n");
            Set<String> genres = item.genres();
            if (genres != null && !genres.isEmpty()) {
                sb.append("🏷️ ").append(String.join(" · ", genres)).append("\n");
            }
            if (item.latestEpisodeTitle() != null && !item.latestEpisodeTitle().isBlank()
                && type.isEpisodeTrackable()) {
                sb.append("📝 最新：").append(item.latestEpisodeTitle()).append("\n");
            }
            if (item.recommendationReason() != null && !item.recommendationReason().isBlank()) {
                sb.append("💡 理由：").append(item.recommendationReason()).append("\n");
            }
            if (item.pageUrl() != null && !item.pageUrl().isBlank()) {
                sb.append("🔗 ").append(item.pageUrl()).append("\n");
            }
            sb.append("➡️ 回复 **").append(type == ContentType.MOVIE ? "想看" : "订阅")
              .append(no).append("** 直接").append(type == ContentType.MOVIE ? "标记想看" : "追更")
              .append("  |  回复 **看过").append(no).append("** / **不喜欢").append(no).append("**\n\n");
            no++;
        }

        sb.append("💡 小贴士：你也可以直接把B站作品链接发给我，我自动帮你加追更！");
        return sb.toString();
    }

    public static String formatSubscriptionList(List<SubscriptionView> list) {
        if (list == null || list.isEmpty()) {
            return "📭 你还没有订阅任何作品哦～\n推荐动漫回复「今日动漫推荐」，\n直接发B站链接我也能自动订阅！";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📋 **我的B站追更订阅**（共").append(list.size()).append("个）\n\n");

        int no = 1;
        for (SubscriptionView s : list) {
            String statusIcon = switch (s.status()) {
                case ACTIVE -> "✅ 追更中";
                case PAUSED -> "⏸️ 已暂停";
                case CANCELLED -> "❌ 已取消";
            };
            String typeName = typeNameOf(s.contentType());
            sb.append(no).append(". [").append(statusIcon).append("] ")
              .append("【").append(typeName).append("】").append(s.title());
            if (s.latestEpisodeNumber() != null && s.contentType().isEpisodeTrackable()) {
                sb.append("（最新").append(s.latestEpisodeNumber()).append("话）");
            }
            sb.append("\n");
            sb.append("   🆔 ").append(s.subscriptionId()).append("\n\n");
            no++;
        }

        sb.append("➡️ 取消订阅：回复 **取消订阅** + 编号/ID\n");
        sb.append("➡️ 暂停追更：回复 **暂停订阅** + 编号\n");
        sb.append("➡️ 立即检查：回复 **检查更新**");
        return sb.toString();
    }

    public static String formatSubscriptionResult(SubscriptionResult r) {
        if (!r.success()) {
            return "❌ 订阅失败：" + (r.message() == null ? "未知原因" : r.message());
        }
        StringBuilder sb = new StringBuilder();
        if (r.alreadySubscribed()) {
            sb.append("ℹ️ 你已经订阅过这部作品了！\n");
        } else {
            sb.append("✅ 订阅成功！\n");
        }
        sb.append("🎬 作品：").append(r.title()).append("\n");
        if (r.latestEpisodeNumber() != null) {
            sb.append("📝 当前：第").append(r.latestEpisodeNumber()).append("话\n");
        }
        sb.append("🆔 订阅编号：").append(r.subscriptionId()).append("\n\n");
        sb.append("以后有新集更新，我会第一时间微信通知你！😉");
        return sb.toString();
    }

    public static String formatOperationResult(OperationResult r) {
        if (r == null) return "❌ 操作失败：未知原因";
        return (r.success() ? "✅ " : "❌ ") + r.message();
    }

    public static String formatCheckResult(int checkedCount, int updateCount,
                                            List<EpisodeUpdateNotification> updates) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 **立即检查更新结果**\n\n");
        sb.append("共检查 ").append(checkedCount).append(" 个订阅");
        if (updateCount == 0) {
            sb.append("，目前没有新更新～你追的番都还在咕咕咕 🕊️");
            return sb.toString();
        }
        sb.append("，发现 ").append(updateCount).append(" 个新更新：\n\n");
        int no = 1;
        for (EpisodeUpdateNotification n : updates) {
            sb.append(no).append(". 🔥").append(n.title())
              .append(" 第").append(n.episodeNumber()).append("话\n");
            if (n.episodeUrl() != null) sb.append("   🔗").append(n.episodeUrl()).append("\n");
            no++;
        }
        return sb.toString();
    }

    public static String formatMarkResult(int index, ContentType type, String stateDesc,
                                          String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 第").append(index).append("部「").append(title == null ? "作品" : title).append("」");
        sb.append("已").append(stateDesc).append("！\n\n");
        if (ContentType.MOVIE.equals(type) && "标记想看".equals(stateDesc)) {
            sb.append("📝 已加入「我的想看清单」，等资源出来我会留意～");
        } else if ("看过".equals(stateDesc)) {
            sb.append("📚 推荐算法已经收到你的反馈，会优化后续推荐哦！");
        } else if ("不喜欢".equals(stateDesc)) {
            sb.append("🚫 类似的作品以后不会再推荐给你啦！");
        } else {
            sb.append("🔔 后续有新动态微信提醒你！");
        }
        return sb.toString();
    }

    public static String formatPreference(BilibiliPreference anime,
                                          BilibiliPreference series,
                                          BilibiliPreference movie) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚙️ **我的B站推荐偏好设置**\n\n");

        if (anime != null) {
            sb.append("🎐 **动漫（BANGUMI）**\n");
            sb.append("  ⏰ 每日推送：").append(anime.isPushEnabled() ? "✅ 开启" : "❌ 关闭")
              .append("（").append(anime.getPushTime() == null ? "未设置" : anime.getPushTime() + " 推送").append("）\n");
            sb.append("  ⭐ 最低评分：").append(String.format("%.1f", anime.getMinimumRating())).append(" 分以上\n");
            sb.append("  📦 推荐数量：每次 ").append(anime.getRecommendationCount()).append(" 部\n");
            if (!anime.getPreferredGenres().isEmpty()) {
                sb.append("  🏷️ 偏好类型：").append(String.join(" · ", anime.getPreferredGenres())).append("\n");
            }
            sb.append("\n");
        }
        if (series != null) {
            sb.append("📺 **剧集电视剧（SERIES）**\n");
            sb.append("  ⏰ 每日推送：").append(series.isPushEnabled() ? "✅ 开启" : "❌ 关闭")
              .append("（").append(series.getPushTime() == null ? "未设置" : series.getPushTime() + " 推送").append("）\n");
            sb.append("  ⭐ 最低评分：").append(String.format("%.1f", series.getMinimumRating())).append(" 分以上\n");
            sb.append("  📦 推荐数量：每次 ").append(series.getRecommendationCount()).append(" 部\n");
            if (!series.getPreferredGenres().isEmpty()) {
                sb.append("  🏷️ 偏好类型：").append(String.join(" · ", series.getPreferredGenres())).append("\n");
            }
            sb.append("\n");
        }
        if (movie != null) {
            sb.append("🎥 **电影（MOVIE）**\n");
            sb.append("  ⏰ 每日推送：").append(movie.isPushEnabled() ? "✅ 开启" : "❌ 关闭")
              .append("（").append(movie.getPushTime() == null ? "未设置" : movie.getPushTime() + " 推送").append("）\n");
            sb.append("  ⭐ 最低评分：").append(String.format("%.1f", movie.getMinimumRating())).append(" 分以上\n");
            sb.append("  📦 推荐数量：每次 ").append(movie.getRecommendationCount()).append(" 部\n");
            if (!movie.getPreferredGenres().isEmpty()) {
                sb.append("  🏷️ 偏好类型：").append(String.join(" · ", movie.getPreferredGenres())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("➡️ 设置命令示例：\n");
        sb.append("  设置动漫推送时间 21:00\n");
        sb.append("  设置剧集最低评分 8.5\n");
        sb.append("  设置电影最低评分 9.0\n");
        sb.append("  关闭动漫推送 / 开启剧集推送");
        return sb.toString();
    }

    public static String formatPreferenceUpdated(String typeName, String fieldName, String value) {
        return "✅ 已更新【" + typeName + "】的" + fieldName + "：当前值 = " + value;
    }

    public static String formatMovieSubscriptionRejected(String title) {
        return "ℹ️ 抱歉～B站电影暂时不支持追更订阅哦！\n\n" +
            "因为电影不会分「集」更新，你可以回复 **想看1** 标记「" +
            (title == null ? "这部电影" : title) + "」到想看清单，\n" +
            "以后我会通过每日推荐留意资源～";
    }

    public String formatResolveFailure(String message) {
        String reason = message == null || message.isBlank()
            ? "暂时无法读取该B站链接"
            : message;
        return "❌ B站链接处理失败：" + reason;
    }

    public static String formatTitleSearchResults(
        String keyword,
        List<BilibiliContent> results
    ) {
        if (results == null || results.isEmpty()) {
            return "🔎 没有找到与“" + keyword
                + "”相关的B站作品，请换个关键词再试。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔎 B站作品搜索：").append(keyword).append("\n\n");
        int index = 1;
        for (BilibiliContent content : results) {
            sb.append(index++).append(". ")
                .append(iconOf(content.getContentType()))
                .append(" **").append(content.getTitle()).append("**");
            if (content.getRating() != null
                && content.getRating() > 0) {
                sb.append(" ⭐")
                    .append(String.format("%.1f", content.getRating()));
            }
            sb.append("\n");
            if (content.getLatestEpisodeTitle() != null
                && !content.getLatestEpisodeTitle().isBlank()) {
                sb.append("   📝 ")
                    .append(content.getLatestEpisodeTitle())
                    .append("\n");
            }
            if (content.getPageUrl() != null
                && !content.getPageUrl().isBlank()) {
                sb.append("   🔗 ")
                    .append(content.getPageUrl())
                    .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public static String formatTodayUpdates(ContentType contentType, int totalUpdated,
                                             List<BilibiliContent> items) {
        String typeName = typeNameOf(contentType);
        String icon = iconOf(contentType);
        StringBuilder sb = new StringBuilder();
        sb.append("📺 **今日B站").append(typeName).append("更新** 📺\n\n");
        sb.append("今天共有 ").append(totalUpdated).append(" 部").append(typeName).append("更新");
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

    private static String typeNameOf(ContentType t) {
        return switch (t) {
            case BANGUMI -> "动漫";
            case SERIES -> "剧集";
            case MOVIE -> "电影";
            case UPLOADER -> "UP主";
        };
    }

    private static String iconOf(ContentType t) {
        return switch (t) {
            case BANGUMI -> "🎐";
            case SERIES -> "📺";
            case MOVIE -> "🎥";
            case UPLOADER -> "🧑‍💻";
        };
    }
}
