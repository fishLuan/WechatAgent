package com.clawbot.wechatbot.feature.document.messaging;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/** 保存用户明确声明的、等待下一份 Word 文档执行的一次性指令。 */
@Component
public class PendingWordDocumentInstructionStore {
    private static final Duration TTL = Duration.ofMinutes(3);
    private static final int MAX_RECENT_MESSAGES = 3;

    private final Map<String, Deque<PendingInstruction>> instructions = new ConcurrentHashMap<>();

    public void put(String userId, String instruction) {
        if (userId == null || userId.isBlank()
            || instruction == null || instruction.isBlank()) {
            return;
        }
        instructions.compute(userId, (key, current) -> {
            Deque<PendingInstruction> queue = current == null
                ? new ArrayDeque<>()
                : current;
            String normalized = instruction.trim();
            PendingInstruction latest = queue.peekFirst();
            if (latest != null && latest.instruction().equals(normalized)) {
                return queue;
            }
            queue.addFirst(new PendingInstruction(normalized, Instant.now()));
            while (queue.size() > MAX_RECENT_MESSAGES) {
                queue.removeLast();
            }
            return queue;
        });
    }

    public String takeLatest(String userId, Predicate<String> matcher) {
        if (userId == null || userId.isBlank()) return null;
        Deque<PendingInstruction> queue = instructions.remove(userId);
        if (queue == null || queue.isEmpty()) return null;
        Instant now = Instant.now();
        for (PendingInstruction pending : queue) {
            if (pending.createdAt().plus(TTL).isBefore(now)) continue;
            if (matcher == null || matcher.test(pending.instruction())) {
                return pending.instruction();
            }
        }
        return null;
    }

    public void clear(String userId) {
        if (userId == null || userId.isBlank()) return;
        instructions.remove(userId);
    }

    private record PendingInstruction(String instruction, Instant createdAt) {
    }
}
