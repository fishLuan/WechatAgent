package com.clawbot.wechatbot.feature.bilibili.application;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliMessageFormatter;
import com.clawbot.wechatbot.feature.bilibili.messaging.PendingSearchResultStore;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliRecommendationService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.RecommendationHistoryService;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 编排作品搜索、搜索结果选择、订阅和观看状态标记。 */
@Service
public final class BilibiliCatalogCommandService {
    private final BilibiliSubscriptionService subscriptions;
    private final BilibiliRecommendationService recommendations;
    private final RecommendationHistoryService history;
    private final BilibiliTitleSearchService titleSearch;
    private final PendingSearchResultStore pendingSearchResults;
    private final BilibiliPreferenceService preferenceService;
    private final BilibiliContentSource contentSource;

    public BilibiliCatalogCommandService(
        @Lazy BilibiliSubscriptionService subscriptions,
        @Lazy BilibiliRecommendationService recommendations,
        RecommendationHistoryService history,
        BilibiliTitleSearchService titleSearch,
        PendingSearchResultStore pendingSearchResults,
        BilibiliPreferenceService preferenceService,
        BilibiliContentSource contentSource
    ) {
        this.subscriptions = subscriptions;
        this.recommendations = recommendations;
        this.history = history;
        this.titleSearch = titleSearch;
        this.pendingSearchResults = pendingSearchResults;
        this.preferenceService = preferenceService;
        this.contentSource = contentSource;
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
        // 推荐路径也自动学习标签（genres 空时补查 B站详情）
        Set<String> genres = item.genres();
        if (genres.isEmpty() && hasText(item.seasonId())) {
            try {
                genres = contentSource.findBySeasonId(item.contentType(), item.seasonId())
                    .map(BilibiliContent::getGenres).orElse(Set.of());
            } catch (Exception ignored) {}
        }
        // 统一存到 BANGUMI，不管 B站返回什么类型
        if (!genres.isEmpty()) {
            var pref = preferenceService.getOrCreate(userId, ContentType.BANGUMI);
            var merged = new LinkedHashSet<>(pref.getPreferredTags());
            merged.addAll(genres);
            preferenceService.setPreferredTags(userId, ContentType.BANGUMI, merged);
        }
        return BilibiliMessageFormatter.formatSubscription(result) + genreSuffix(genres);
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

    public String showSearchResultByIndex(String userId, int index) {
        if (index < 1) return "❌ 作品编号不正确。";
        BilibiliContent item = pendingSearchResults.findByItemNumber(userId, index);
        if (item == null) return "❌ 找不到第 " + index + " 个影视结果，请重新搜索作品。";
        return BilibiliMessageFormatter.formatSearchResults(
            item.getTitle(), List.of(item));
    }

    public String markByIndex(String userId, Integer index, String state) {
        if (index == null || index < 1) return "❌ 要标记的编号不正确。";
        RecommendedContent item = recommendations.findPendingItem(userId, index);
        if (item == null) return "❌ 找不到第 " + index + " 个推荐，请先获取最新推荐。";
        String normal = normalizeState(state);
        switch (normal) {
            case "want_to_watch" -> history.markWantToWatch(
                userId, item.contentType(), item.contentId(), item.title());
            case "watched" -> recommendations.markWatched(userId, index);
            case "disliked" -> recommendations.markDisliked(userId, index);
            default -> throw new IllegalArgumentException("未知标记状态");
        }
        // 标记后自动学习/反学习标签（genres 空时补查 B站详情）
        Set<String> markGenres = item.genres();
        if (markGenres.isEmpty() && hasText(item.seasonId())) {
            try {
                markGenres = contentSource.findBySeasonId(item.contentType(), item.seasonId())
                    .map(BilibiliContent::getGenres).orElse(Set.of());
            } catch (Exception ignored) {}
        }
        int w = "want_to_watch".equals(normal) ? 2 : 1;
        if (!"disliked".equals(normal)) learnTags(userId, item.contentType(), markGenres, w);
        else unlearnTags(userId, item.contentType(), markGenres);
        return "✅ 已将《" + item.title() + "》标记" + stateDescription(state) + "。"
            + genreSuffix(markGenres);
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
            String normal = normalizeState(state);
            switch (normal) {
                case "want_to_watch" -> history.markWantToWatch(
                    userId, exact.getContentType(), exact.getContentId(), exact.getTitle());
                case "watched" -> history.markWatched(
                    userId, exact.getContentType(), exact.getContentId(), exact.getTitle());
                case "disliked" -> history.markDisliked(
                    userId, exact.getContentType(), exact.getContentId(), exact.getTitle());
                default -> throw new IllegalArgumentException("未知标记状态");
            }
            // 标记后自动学习/反学习标签
            Set<String> tags = new LinkedHashSet<>();
            if (exact.getTags() != null) tags.addAll(exact.getTags());
            tags.addAll(exact.getGenres());
            int w = "want_to_watch".equals(normal) ? 2 : 1;
            if (!"disliked".equals(normal)) learnTags(userId, exact.getContentType(), tags, w);
            else unlearnTags(userId, exact.getContentType(), tags);
            return "✅ 已将《" + exact.getTitle() + "》标记为" + stateDescription(state) + "。"
                + genreSuffix(tags);
        } catch (Exception error) {
            return failure("标记作品失败", error);
        }
    }

    private String genreSuffix(Set<String> genres) {
        if (genres == null || genres.isEmpty()) return "";
        return "\n🏷️ " + String.join("、", genres);
    }

    private void learnTags(String userId, ContentType type, Set<String> newTags, int weight) {
        if (newTags == null || newTags.isEmpty()) return;
        preferenceService.addTagWeight(userId, type, newTags, weight);
    }

    private void unlearnTags(String userId, ContentType type, Set<String> removeTags) {
        if (removeTags == null || removeTags.isEmpty()) return;
        var pref = preferenceService.getOrCreate(userId, type);
        var merged = new LinkedHashSet<>(pref.getPreferredTags());
        merged.removeAll(removeTags);
        preferenceService.setPreferredTags(userId, type, merged);
    }

    private String subscribe(String userId, BilibiliContent content) {
        if (content.isFinished()) return "ℹ️ 《" + content.getTitle() + "》已经完结，无需追更订阅。";
        SubscriptionResult result = hasText(content.getSeasonId())
            ? subscriptions.subscribeBySeasonId(userId, content.getContentType(), content.getSeasonId())
            : subscriptions.subscribeByContentId(userId, content.getContentType(), content.getContentId());
        // 自动学习：订阅权 3 > 想看权 2 > 看过权 1
        Set<String> learned = new LinkedHashSet<>();
        if (content.getTags() != null) learned.addAll(content.getTags());
        learned.addAll(content.getGenres());
        // 搜索结果可能不带 genres/tags，补查 B站详情
        if (learned.isEmpty() && hasText(content.getSeasonId())) {
            try {
                contentSource.findBySeasonId(content.getContentType(), content.getSeasonId())
                    .ifPresent(d -> {
                        if (d.getTags() != null) learned.addAll(d.getTags());
                        learned.addAll(d.getGenres());
                    });
            } catch (Exception ignored) {}
        }
        // 统一存到 BANGUMI
        if (!learned.isEmpty()) {
            var pref = preferenceService.getOrCreate(userId, ContentType.BANGUMI);
            var merged = new LinkedHashSet<>(pref.getPreferredTags());
            merged.addAll(learned);
            preferenceService.setPreferredTags(userId, ContentType.BANGUMI, merged);
        }
        return BilibiliMessageFormatter.formatSubscription(result) + genreSuffix(learned);
    }

    private List<BilibiliContent> search(String title) throws Exception {
        if (!hasText(title)) return List.of();
        return titleSearch.search(title.trim());
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
