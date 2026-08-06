package com.clawbot.wechatbot.intent;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps short-lived domain context isolated per WeChat user. */
@Component
public final class ConversationDomainStore {
    public enum Domain { BILIBILI, WEREAD }

    private static final Duration TTL = Duration.ofHours(2);
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void activate(String userId, Domain domain) {
        if (userId == null || userId.isBlank() || domain == null) return;
        entries.put(userId, new Entry(domain, Instant.now()));
    }

    public boolean isActive(String userId, Domain domain) {
        if (userId == null || domain == null) return false;
        Entry entry = entries.get(userId);
        if (entry == null) return false;
        if (!entry.createdAt().plus(TTL).isAfter(Instant.now())) {
            entries.remove(userId, entry);
            return false;
        }
        return entry.domain() == domain;
    }

    private record Entry(Domain domain, Instant createdAt) {}
}
