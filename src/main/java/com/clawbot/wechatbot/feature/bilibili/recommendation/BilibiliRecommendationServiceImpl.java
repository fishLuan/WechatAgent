package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationResult;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每日高分推荐服务实现。
 *
 * <p>从 {@link BilibiliContentSource} 获取候选作品，经评分、去重后生成推荐列表。
 * 动漫（BANGUMI）、剧集（SERIES）、电影（MOVIE）使用各自独立的候选池和偏好。</p>
 */
@Service
public class BilibiliRecommendationServiceImpl implements BilibiliRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliRecommendationServiceImpl.class);
    /** 每次从数据源拉取的候选作品数，经过滤打分后取最优 */
    private static final int CANDIDATE_POOL_SIZE = 30;

    private final BilibiliContentSource contentSource;
    private final BilibiliPreferenceService preferenceService;
    private final RecommendationHistoryService historyService;
    private final PendingRecommendationStore pendingStore;
    private final BilibiliProperties properties;
    private final BilibiliContentRepository contentRepository;

    public BilibiliRecommendationServiceImpl(
            BilibiliContentSource contentSource,
            BilibiliPreferenceService preferenceService,
            RecommendationHistoryService historyService,
            PendingRecommendationStore pendingStore,
            BilibiliProperties properties,
            BilibiliContentRepository contentRepository) {
        this.contentSource = contentSource;
        this.preferenceService = preferenceService;
        this.historyService = historyService;
        this.pendingStore = pendingStore;
        this.properties = properties;
        this.contentRepository = contentRepository;
    }

    @Override
    public RecommendationResult recommend(
            String wechatUserId, ContentType contentType, int count) {
        return generateRecommendation(wechatUserId, contentType, count, false);
    }

    @Override
    public RecommendationResult refresh(
            String wechatUserId, ContentType contentType, int count) {
        return generateRecommendation(wechatUserId, contentType, count, true);
    }

    /**
     * 查找待处理的推荐条目（无 contentType 时按用户最后活跃类型查找）。
     */
    @Override
    public RecommendedContent findPendingItem(String wechatUserId, int itemNumber) {
        if (itemNumber < 1) return null;

        // 尝试按最后活跃类型查找
        ContentType lastType = pendingStore.getLastActiveType(wechatUserId);
        if (lastType != null) {
            RecommendedContent item = pendingStore.findByItemNumber(
                wechatUserId, lastType, itemNumber);
            if (item != null) return item;
        }

        // 回退：遍历所有内容类型查找
        for (ContentType type : ContentType.values()) {
            RecommendedContent item = pendingStore.findByItemNumber(
                wechatUserId, type, itemNumber);
            if (item != null) {
                pendingStore.setLastActiveType(wechatUserId, type);
                return item;
            }
        }
        return null;
    }

    @Override
    public void markWatched(String wechatUserId, int itemNumber) {
        markState(wechatUserId, itemNumber, "WATCHED");
    }

    @Override
    public void markDisliked(String wechatUserId, int itemNumber) {
        markState(wechatUserId, itemNumber, "DISLIKED");
    }

    /**
     * 主动查询式的推荐管道，供外部模块直接获取推荐结果。
     *
     * @return 格式化后的推荐文本（供日志或调试使用）
     */
    public String describeRecommendation(
            String wechatUserId, ContentType contentType, int count) {
        RecommendationResult result = recommend(wechatUserId, contentType, count);
        StringBuilder sb = new StringBuilder();
        sb.append("📺 ").append(contentType.name()).append(" 推荐\n");
        sb.append("━━━━━━━━━━━━━━━\n");
        int idx = 1;
        for (RecommendedContent item : result.items()) {
            sb.append(idx++).append(". ").append(item.title());
            if (item.rating() != null) {
                sb.append(" ⭐").append(item.rating());
            }
            if (item.recommendationReason() != null && !item.recommendationReason().isBlank()) {
                sb.append(" (").append(item.recommendationReason()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---- internal ----

    private RecommendationResult generateRecommendation(
            String wechatUserId, ContentType contentType, int count, boolean forceRefresh) {

        if (forceRefresh) {
            pendingStore.remove(wechatUserId, contentType);
        }

        // 1. 获取用户偏好（若无则用全局默认）
        BilibiliPreference pref = preferenceService.getOrCreate(wechatUserId, contentType);
        double minRating = pref.getMinimumRating();
        int targetCount = (count > 0) ? count : pref.getRecommendationCount();
        Set<String> userGenres = pref.getPreferredGenres();
        Set<String> userTags = pref.getPreferredTags();
        Map<String, Integer> tagWeights = pref.getTagWeights();

        // 2. 有标签偏好 → 纯标签推荐；无标签 → 高分推荐
        List<BilibiliContent> candidates;
        if (!userTags.isEmpty()) {
            // 标签模式：只用标签搜索结果
            candidates = tagBasedCandidates(contentType, userTags, targetCount);
        } else {
            // 高分模式：DB 候选池（原有逻辑不变）
            try {
                candidates = contentRepository
                    .findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(contentType, 0.0)
                    .stream().limit(CANDIDATE_POOL_SIZE).toList();
            } catch (Exception e) {
                log.warn("读取 {} Mongo 候选池失败: {}", contentType, e.getMessage());
                candidates = List.of();
            }
            if (candidates.isEmpty()) {
                try {
                    candidates = contentSource.findCandidates(contentType, CANDIDATE_POOL_SIZE);
                } catch (Exception e) {
                    log.error("{} 候选池失败: {}", contentType, e.getMessage());
                    return emptyResult(wechatUserId, contentType);
                }
            }
        }

        if (candidates == null || candidates.isEmpty()) {
            log.warn("{} 无可推荐候选", contentType);
            return emptyResult(wechatUserId, contentType);
        }

        // 3. 获取已排除的内容 ID
        Set<String> excludedIds = new LinkedHashSet<>(
            historyService.findExcludedContentIds(wechatUserId, contentType));

        // 4. 过滤：排除低评分、已表态作品（有标签匹配的放宽评分限制）
        List<BilibiliContent> filtered = candidates.stream()
            .filter(c -> {
                if (c.getRating() == null || c.getRating() >= minRating) return true;
                // 有标签匹配的，评分不低于 minRating-2 即可
                if (!userTags.isEmpty() && c.getTags() != null
                    && c.getTags().stream().anyMatch(userTags::contains)) {
                    return c.getRating() >= Math.max(5.0, minRating - 2.0);
                }
                return false;
            })
            .filter(c -> !excludedIds.contains(c.getContentId()))
            .toList();

        if (filtered.isEmpty()) {
            log.info("{} 过滤后无候选（最低评分={}, 已排除 {} 部）",
                contentType, minRating, excludedIds.size());
            return emptyResult(wechatUserId, contentType);
        }

        // 5. 打分排序
        List<BilibiliContent> ranked = contentType == ContentType.BANGUMI
            ? RecommendationCandidateScorer.scoreAndRank(
                filtered, targetCount, userGenres, userTags)
            : FilmSeriesRecommendationScorer.scoreAndRank(
                contentType, filtered, targetCount,
                userGenres, userTags, tagWeights);

        // 6. 转换为推荐条目
        List<RecommendedContent> items = ranked.stream()
            .map(c -> toRecommendedContent(
                c, userGenres, userTags, tagWeights))
            .toList();

        // 7. 记录推荐历史
        historyService.recordRecommendations(wechatUserId, contentType, items);

        // 8. 缓存到暂存区
        pendingStore.put(wechatUserId, contentType, items);
        pendingStore.setLastActiveType(wechatUserId, contentType);

        log.info("已为 {} 生成 {} 推荐（候选 {}/过滤 {}/最终 {}）",
            wechatUserId, contentType, candidates.size(), filtered.size(), items.size());

        return new RecommendationResult(
            wechatUserId, contentType, items, Instant.now());
    }

    private RecommendedContent toRecommendedContent(
            BilibiliContent c, Set<String> userGenres, Set<String> userTags,
            Map<String, Integer> tagWeights) {
        String reason = c.getContentType() == ContentType.BANGUMI
            ? RecommendationCandidateScorer.generateReason(c, userGenres, userTags)
            : FilmSeriesRecommendationScorer.reason(
                c.getContentType(), c, userGenres, userTags, tagWeights);
        // 保持动漫原有理由格式；电影和电视剧由独立评分器生成加权理由。
        if (c.getContentType() == ContentType.BANGUMI && !userTags.isEmpty()) {
            Set<String> matched = new LinkedHashSet<>();
            if (c.getTags() != null) {
                c.getTags().stream().filter(userTags::contains).forEach(matched::add);
            }
            if (!matched.isEmpty()) {
                reason = reason.replace("，标签匹配", ""); // 去掉旧标记，重新加上具体名字
                reason += "，标签匹配：" + String.join("、", matched);
            }
        }
        String latestEpTitle = c.getLatestEpisodeTitle();
        if (latestEpTitle == null && c.getLatestEpisodeNumber() != null) {
            latestEpTitle = "第 " + c.getLatestEpisodeNumber() + " 集";
        }
        String pageUrl = c.getPageUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            String id = c.getSeasonId() != null ? c.getSeasonId() : c.getContentId();
            pageUrl = "https://www.bilibili.com/bangumi/play/ss" + id;
        }
        return new RecommendedContent(
            c.getContentType(),
            c.getContentId(),
            c.getSeasonId(),
            c.getTitle(),
            c.getRating(),
            c.getGenres(),
            pageUrl,
            latestEpTitle,
            reason);
    }

    private void markState(String wechatUserId, int itemNumber, String state) {
        // 尝试所有内容类型
        for (ContentType type : ContentType.values()) {
            RecommendedContent item = pendingStore.findByItemNumber(
                wechatUserId, type, itemNumber);
            if (item == null) continue;

            switch (state) {
                case "WATCHED" ->
                    historyService.markWatched(wechatUserId, type, item.contentId());
                case "DISLIKED" ->
                    historyService.markDisliked(wechatUserId, type, item.contentId());
            }
            log.info("用户 {} 将 {} [{}] 标记为 {}",
                wechatUserId, type, item.title(), state);
            return;
        }
        log.warn("用户 {} 标记失败：未找到序号 {} 对应的推荐项", wechatUserId, itemNumber);
    }

    /** 标签模式：用每个标签搜索 B站，补 detail tags，合并去重，并记录每个内容匹配的标签。 */
    private List<BilibiliContent> tagBasedCandidates(
            ContentType contentType, Set<String> userTags, int targetCount) {
        Map<String, BilibiliContent> merged = new LinkedHashMap<>();
        Map<String, Set<String>> tagMap = new LinkedHashMap<>(); // contentId → 匹配的标签
        for (String tag : userTags) {
            try {
                List<BilibiliContent> tagged = contentSource.searchByTitle(tag, targetCount + 3);
                int enriched = 0;
                for (BilibiliContent c : tagged) {
                    merged.putIfAbsent(c.getContentId(), c);
                    tagMap.computeIfAbsent(c.getContentId(), k -> new LinkedHashSet<>()).add(tag);
                    if (enriched < 3 && c.getSeasonId() != null && !c.getSeasonId().isBlank()) {
                        try {
                            contentSource.findBySeasonId(contentType, c.getSeasonId())
                                .ifPresent(d -> {
                                    merged.put(c.getContentId(), d);
                                    tagMap.computeIfAbsent(c.getContentId(), k -> new LinkedHashSet<>()).add(tag);
                                });
                            enriched++;
                        } catch (Exception ignored) {}
                    }
                }
                if (tagged.isEmpty()) {
                    List<BilibiliContent> descResults = contentSource.searchByTitle(
                        tag + switch (contentType) {
                            case BANGUMI -> " 动漫"; case MOVIE -> " 电影";
                            case SERIES -> " 剧集"; default -> "";
                        }, targetCount + 3);
                    for (BilibiliContent c : descResults) {
                        merged.putIfAbsent(c.getContentId(), c);
                        tagMap.computeIfAbsent(c.getContentId(), k -> new LinkedHashSet<>()).add(tag);
                    }
                }
            } catch (Exception e) {
                log.warn("{} 标签'{}'搜索失败: {}", contentType, tag, e.getMessage());
            }
        }
        // 把搜索关键词合入 tags，供后续多样化排序和标签匹配展示使用
        for (var entry : tagMap.entrySet()) {
            BilibiliContent c = merged.get(entry.getKey());
            if (c != null) {
                Set<String> combined = new LinkedHashSet<>(c.getTags());
                combined.addAll(entry.getValue());
                c.setTags(combined);
            }
        }
        log.info("{} 标签模式候选共 {} 条", contentType, merged.size());
        return new ArrayList<>(merged.values());
    }

    private static RecommendationResult emptyResult(
            String wechatUserId, ContentType contentType) {
        return new RecommendationResult(
            wechatUserId, contentType, List.of(), Instant.now());
    }
}
