package com.clawbot.wechatbot.feature.voice;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;

@Service
public class VoiceReplyContextStore {
    private static final Duration TTL = Duration.ofDays(7);
    private final VoiceReplyContextRepository repository;

    public VoiceReplyContextStore(VoiceReplyContextRepository repository) {
        this.repository = repository;
    }

    public void save(String userId, String text) {
        String owner = normalize(userId);
        String content = text == null ? "" : text.trim();
        if (owner.isBlank() || content.isBlank()) return;
        long now = System.currentTimeMillis();
        VoiceReplyContext context = new VoiceReplyContext();
        context.setUserId(owner);
        context.setText(content);
        context.setUpdatedAt(now);
        context.setExpiresAt(new Date(now + TTL.toMillis()));
        repository.save(context);
    }

    public Optional<String> find(String userId) {
        String owner = normalize(userId);
        if (owner.isBlank()) return Optional.empty();
        return repository.findById(owner)
            .filter(context -> context.getExpiresAt() != null
                && context.getExpiresAt().after(new Date()))
            .map(VoiceReplyContext::getText)
            .filter(text -> text != null && !text.isBlank());
    }

    private String normalize(String userId) {
        return userId == null ? "" : userId.trim();
    }
}
