package com.clawbot.wechatbot.feature.bilibili.application;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliMessageFormatter;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliUpdateRange;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.recommendation.BilibiliPreferenceService;
import com.clawbot.wechatbot.feature.bilibili.recommendation.RecommendationHistoryService;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 查询指定时间范围内的 B 站更新，并应用用户过滤偏好。 */
@Service
public final class BilibiliUpdateQueryService {
    private final BilibiliContentRepository contentRepository;
    private final BilibiliContentSource contentSource;
    private final RecommendationHistoryService history;
    private final BilibiliPreferenceService preferences;

    public BilibiliUpdateQueryService(
        BilibiliContentRepository contentRepository,
        BilibiliContentSource contentSource,
        RecommendationHistoryService history,
        BilibiliPreferenceService preferences
    ) {
        this.contentRepository = contentRepository;
        this.contentSource = contentSource;
        this.history = history;
        this.preferences = preferences;
    }

    public String query(String userId, ContentType requestedType, BilibiliUpdateRange requestedRange) {
        ContentType contentType = requestedType == null ? ContentType.BANGUMI : requestedType;
        BilibiliUpdateRange range = requestedRange == null ? BilibiliUpdateRange.TODAY : requestedRange;
        try {
            Instant end = Instant.now();
            Instant start = range.from(end);
            List<BilibiliContent> updates = contentRepository.findUpdatesBetween(contentType, start, end);
            if (updates == null || updates.isEmpty()) {
                updates = loadFromSource(contentType, start, end);
            }
            if (updates == null || updates.isEmpty()) {
                return range.displayName() + "暂时没有查到" + typeNameOf(contentType)
                    + "更新。若本地作品库尚未刷新，也可以稍后再试。";
            }

            int totalCount = updates.size();
            int count = resolveDefaultCount(userId, contentType);
            List<String> excluded = history.findExcludedContentIds(userId, contentType);
            List<BilibiliContent> filtered = updates.stream()
                .filter(content -> !excluded.contains(content.getContentId()))
                .limit(count)
                .toList();
            return BilibiliMessageFormatter.formatUpdates(
                contentType, range.displayName(), totalCount, filtered);
        } catch (LiveQueryException error) {
            return "暂时无法获取B站实时更新数据，请稍后重试。";
        } catch (Exception error) {
            return failure("获取" + range.displayName() + "更新失败", error);
        }
    }

    private List<BilibiliContent> loadFromSource(
        ContentType contentType, Instant start, Instant end
    ) {
        try {
            List<BilibiliContent> updates = contentSource.findUpdates(contentType, start, end);
            if (updates != null && !updates.isEmpty()) contentRepository.saveAll(updates);
            return updates;
        } catch (Exception error) {
            System.err.println("[BILIBILI] 更新时间实时查询失败: " + error.getMessage());
            throw new LiveQueryException(error);
        }
    }

    private int resolveDefaultCount(String userId, ContentType contentType) {
        try {
            BilibiliPreference preference = preferences.getOrCreate(userId, contentType);
            return Math.max(1, preference.getRecommendationCount());
        } catch (Exception error) {
            return 3;
        }
    }

    private static String typeNameOf(ContentType type) {
        return switch (type) {
            case BANGUMI -> "动漫";
            case SERIES -> "剧集";
            case MOVIE -> "电影";
            case UPLOADER -> "UP主";
        };
    }

    private String failure(String action, Exception error) {
        String reason = error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage();
        System.err.println("[BILIBILI] " + action + "：" + reason);
        return "❌ " + action + "：" + reason;
    }

    private static final class LiveQueryException extends RuntimeException {
        private LiveQueryException(Throwable cause) {
            super(cause);
        }
    }
}
