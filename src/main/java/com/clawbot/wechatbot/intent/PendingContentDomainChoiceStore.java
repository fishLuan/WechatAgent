package com.clawbot.wechatbot.intent;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-user pending choice for a title that exists as both a book and video work. */
@Component
public final class PendingContentDomainChoiceStore {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Map<String, PendingChoice> choices = new ConcurrentHashMap<>();

    public void put(String userId, String title) {
        if (userId != null && !userId.isBlank() && title != null && !title.isBlank()) {
            choices.put(userId, new PendingChoice(title.trim(), Instant.now()));
        }
    }

    public PendingChoice get(String userId) {
        if (userId == null) return null;
        PendingChoice choice = choices.get(userId);
        if (choice != null && !choice.createdAt().plus(TTL).isAfter(Instant.now())) {
            choices.remove(userId, choice);
            return null;
        }
        return choice;
    }

    public PendingChoice consume(String userId) {
        PendingChoice choice = get(userId);
        if (choice != null) choices.remove(userId, choice);
        return choice;
    }

    public record PendingChoice(String title, Instant createdAt) {}
}
