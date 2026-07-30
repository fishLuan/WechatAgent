package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂存每个微信用户最近一次看到的 B 站搜索结果，支持“订阅第三个”等后续引用。
 */
@Component
public final class PendingSearchResultStore {
    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private final Map<String, PendingSearchResult> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public PendingSearchResultStore() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    PendingSearchResultStore(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 必须大于 0");
        }
    }

    public void put(String wechatUserId, List<BilibiliContent> items) {
        Objects.requireNonNull(wechatUserId, "wechatUserId");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            remove(wechatUserId);
            return;
        }
        store.put(
            wechatUserId,
            new PendingSearchResult(List.copyOf(items), clock.instant()));
    }

    public BilibiliContent findByItemNumber(String wechatUserId, int itemNumber) {
        if (wechatUserId == null || itemNumber < 1) return null;
        PendingSearchResult pending = store.get(wechatUserId);
        if (pending == null) return null;
        if (!pending.createdAt().plus(ttl).isAfter(clock.instant())) {
            store.remove(wechatUserId, pending);
            return null;
        }
        int index = itemNumber - 1;
        return index < pending.items().size() ? pending.items().get(index) : null;
    }

    public void remove(String wechatUserId) {
        if (wechatUserId != null) store.remove(wechatUserId);
    }

    private record PendingSearchResult(
        List<BilibiliContent> items,
        Instant createdAt
    ) {
    }
}
