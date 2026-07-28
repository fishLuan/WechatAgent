package com.clawbot.wechatbot.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(name = "clawbot.memory.enabled", havingValue = "false")
public class InMemoryConversationMemoryService implements ConversationMemoryService {
    private final MemoryProperties properties;
    private final MemoryKeyFactory keys;
    private final Map<String, ConversationMemory> memories = new ConcurrentHashMap<>();
    private final Map<String, Instant> processedMessages = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public InMemoryConversationMemoryService(
        MemoryProperties properties, MemoryKeyFactory keys
    ) {
        this.properties = properties;
        this.keys = keys;
    }

    @Override
    public ConversationMemory get(String userId) {
        ConversationMemory memory = memories.get(keys.memoryId(userId));
        return memory == null
            ? ConversationMemory.empty(
                keys.memoryId(userId), keys.namespace(), keys.userKey(userId))
            : memory.copy();
    }

    @Override
    public ConversationMemory appendTurn(
        String userId, String userText, String assistantReply
    ) {
        String id = keys.memoryId(userId);
        synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
            ConversationMemory memory = memories.computeIfAbsent(
                id,
                ignored -> ConversationMemory.empty(id, keys.namespace(), keys.userKey(userId))
            );
            Instant now = Instant.now();
            if (memory.getCreatedAt() == null) memory.setCreatedAt(now);
            memory.getRecentMessages().add(new ConversationMessage("user", userText, now));
            memory.getRecentMessages().add(
                new ConversationMessage("assistant", assistantReply, now));
            int maxMessages = properties.getRecentTurns() * 2;
            if (memory.getRecentMessages().size() > maxMessages) {
                memory.setRecentMessages(new ArrayList<>(
                    memory.getRecentMessages().subList(
                        memory.getRecentMessages().size() - maxMessages,
                        memory.getRecentMessages().size()
                    )
                ));
            }
            memory.setTurnCounter(memory.getTurnCounter() + 1);
            memory.setUpdatedAt(now);
            return memory.copy();
        }
    }

    @Override
    public ConversationMemory replaceSummary(String userId, String summary) {
        String id = keys.memoryId(userId);
        synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
            ConversationMemory memory = memories.computeIfAbsent(
                id,
                ignored -> ConversationMemory.empty(id, keys.namespace(), keys.userKey(userId))
            );
            Instant now = Instant.now();
            if (memory.getCreatedAt() == null) memory.setCreatedAt(now);
            memory.setLongTermSummary(summary);
            memory.setUpdatedAt(now);
            return memory.copy();
        }
    }

    @Override
    public boolean markMessageProcessed(String userId, Long messageId) {
        Instant now = Instant.now();
        Instant expires = now.plus(
            Duration.ofMinutes(properties.getMessageDedupTtlMinutes()));
        String id = keys.deduplicationId(userId, messageId);
        AtomicBoolean accepted = new AtomicBoolean(false);
        processedMessages.compute(id, (ignored, currentExpiry) -> {
            if (currentExpiry == null || !currentExpiry.isAfter(now)) {
                accepted.set(true);
                return expires;
            }
            return currentExpiry;
        });
        return accepted.get();
    }

    @Override
    public void clear(String userId) {
        String id = keys.memoryId(userId);
        memories.remove(id);
        locks.remove(id);
        String prefix = id + ":";
        processedMessages.keySet().removeIf(key -> key.startsWith(prefix));
    }
}