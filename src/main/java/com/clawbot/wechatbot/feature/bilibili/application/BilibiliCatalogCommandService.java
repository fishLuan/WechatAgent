package com.clawbot.wechatbot.feature.bilibili.application;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliMessageFormatter;
import com.clawbot.wechatbot.feature.bilibili.messaging.PendingSearchResultStore;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.RecommendationHistoryService;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** 编排作品搜索、搜索结果选择、订阅和观看状态标记。 */
@Service
public final class BilibiliCatalogCommandService {
    private final BilibiliSubscriptionService subscriptions;
    private final BilibiliRecommendationService recommendations;
    private final RecommendationHistoryService history;
    private final BilibiliContentSource contentSource;
    private final BilibiliProperties properties;
    private final PendingSearchResultStore pendingSearchResults;

    public BilibiliCatalogCommandService(
        @Lazy BilibiliSubscriptionService subscriptions,
        @Lazy BilibiliRecommendationService recommendations,
        RecommendationHistoryService history,
        BilibiliContentSource contentSource,
        BilibiliProperties properties,
        PendingSearchResultStore pendingSearchResults
    ) {
        this.subscriptions = subscriptions;
        this.recommendations = recommendations;
        this.history = history;
        this.contentSource = contentSource;
        this.properties = properties;
        this.pendingSearchResults = pendingSearchResults;
    }

    public String subscribeByIndex(String userId, Integer index, ContentType ignoredType) {
        if (index == null || index < 1) return "❌ 推荐编号不正确。";
        BilibiliContent searchedItem = pendingSearchResults.findByItemNumber(userId, index);
        if (searchedItem != null) return subscribe(userId, searchedItem);

        RecommendedContent item = recommendations.findPendingItem(userId, index);
        if (item == null) return "❌ 找不到第 " + index + " 个推荐，请先获取最新推荐。";
        SubscriptionResult result = hasText(item.seasonId())
            ? subscriptions.subscribeBySeasonId(userId, item.contentType(), item.seasonId())
            : subscriptions.subscribeByContentId(userId, item.contentType(), item.contentId());
        return BilibiliMessageFormatter.formatSubscription(result);
    }

    public String subscribeByTitle(String userId, String title) {
        try {
            List<BilibiliContent> matches = search(title);
            if (matches.isEmpty()) return "❌ 没有找到作品“" + title + "”。";
            BilibiliContent exact = uniqueExactMatch(title, matches);
            if (exact == null) {
                pendingSearchResults.put(userId, matches);
                return "找到多个相关作品，请回复“订阅第几个”或发送对应链接：\n\n"
                    + BilibiliMessageFormatter.formatSearchResults(title, matches);
            }
            return subscribe(userId, exact);
        } catch (Exception error) {
            return failure("按名称订阅失败", error);
        }
    }

    public String searchByTitle(String userId, String title) {
        try {
            List<BilibiliContent> matches = search(title);
            pendingSearchResults.put(userId, matches);
            return BilibiliMessageFormatter.formatSearchResults(title, matches);
        } catch (Exception error) {
            return failure("搜索作品失败", error);
        }
    }

    public String markByIndex(String userId, Integer index, String state) {
        if (index == null || index < 1) return "❌ 要标记的编号不正确。";
        RecommendedContent item = recommendations.findPendingItem(userId, index);
        if (item == null) return "❌ 找不到第 " + index + " 个推荐，请先获取最新推荐。";
        switch (normalizeState(state)) {
            case "want_to_watch" -> history.markWantToWatch(
                userId, item.contentType(), item.contentId(), item.title());
            case "watched" -> recommendations.markWatched(userId, index);
            case "disliked" -> recommendations.markDisliked(userId, index);
            default -> throw new IllegalArgumentException("未知标记状态");
        }
        return "✅ 已将《" + item.title() + "》标记" + stateDescription(state) + "。";
    }

    public String markByTitle(String userId, String title, String state) {
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
            return "✅ 已将《" + exact.getTitle() + "》标记为" + stateDescription(state) + "。";
        } catch (Exception error) {
            return failure("标记作品失败", error);
        }
    }

    private String subscribe(String userId, BilibiliContent content) {
        if (content.isFinished()) return "ℹ️ 《" + content.getTitle() + "》已经完结，无需追更订阅。";
        SubscriptionResult result = hasText(content.getSeasonId())
            ? subscriptions.subscribeBySeasonId(userId, content.getContentType(), content.getSeasonId())
            : subscriptions.subscribeByContentId(userId, content.getContentType(), content.getContentId());
        return BilibiliMessageFormatter.formatSubscription(result);
    }

    private List<BilibiliContent> search(String title) throws Exception {
        if (!hasText(title)) return List.of();
        return contentSource.searchByTitle(title.trim(), properties.getSearchResultCount());
    }

    private BilibiliContent uniqueExactMatch(String requestedTitle, List<BilibiliContent> matches) {
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
