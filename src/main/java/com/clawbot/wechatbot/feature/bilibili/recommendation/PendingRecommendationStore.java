package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存暂存当前用户最近一次的推荐列表，用于"订阅2""想看2"等序号引用。
 *
 * <p>key = wechatUserId + ":" + contentType.name()<br>
 * 重启后丢失，但用户通常实时回复，影响可控。
 */
@Component
public class PendingRecommendationStore {

    private final Map<String, PendingRecommendation> store = new ConcurrentHashMap<>();
    private final Map<String, ContentType> lastActiveType = new ConcurrentHashMap<>();

    /**
     * 保存一次推荐结果供后续序号引用。
     */
    public void put(String wechatUserId, ContentType contentType, List<RecommendedContent> items) {
        Objects.requireNonNull(wechatUserId, "wechatUserId");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(items, "items");
        store.put(key(wechatUserId, contentType),
            new PendingRecommendation(List.copyOf(items), Instant.now()));
    }

    /**
     * 清除指定用户的推荐缓存（如用户要求刷新时）。
     */
    public void remove(String wechatUserId, ContentType contentType) {
        store.remove(key(wechatUserId, contentType));
    }

    /**
     * 按用户、内容类型和序号（1-based）查找待处理的推荐条目。
     *
     * @return 对应序号的推荐内容，序号越界或缓存不存在时返回 {@code null}
     */
    public RecommendedContent findByItemNumber(
            String wechatUserId, ContentType contentType, int itemNumber) {
        if (itemNumber < 1) return null;
        PendingRecommendation pending = store.get(key(wechatUserId, contentType));
        if (pending == null) return null;
        int index = itemNumber - 1;
        if (index >= pending.items().size()) return null;
        return pending.items().get(index);
    }

    /**
     * 获取用户最近一次推荐的完整列表。
     */
    public List<RecommendedContent> getPendingItems(
            String wechatUserId, ContentType contentType) {
        PendingRecommendation pending = store.get(key(wechatUserId, contentType));
        return pending == null ? List.of() : pending.items();
    }

    /**
     * 检查是否存在未过期的待处理推荐（2小时内有效）。
     */
    public boolean hasRecentPending(String wechatUserId, ContentType contentType) {
        PendingRecommendation pending = store.get(key(wechatUserId, contentType));
        if (pending == null) return false;
        return pending.createdAt().isAfter(Instant.now().minusSeconds(7200));
    }

    // ---- internal ----

    private static String key(String wechatUserId, ContentType contentType) {
        return wechatUserId + ":" + contentType.name();
    }

    /** 记录用户最后一次查看推荐的内容类型。*/
    public void setLastActiveType(String wechatUserId, ContentType contentType) {
        lastActiveType.put(wechatUserId, contentType);
    }

    /** 获取用户最后一次查看推荐的内容类型。*/
    public ContentType getLastActiveType(String wechatUserId) {
        return lastActiveType.get(wechatUserId);
    }

    private record PendingRecommendation(List<RecommendedContent> items, Instant createdAt) {
        PendingRecommendation {
            items = List.copyOf(items);
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }
}
