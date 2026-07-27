package com.clawbot.wechatbot.service.reply;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理等待用户选择发送方式的长回复。
 *
 * <p>暂存内容按微信用户 ID 隔离，并通过过期时间和数量上限限制内存占用。</p>
 */
public final class LongReplyManager {
    private static final int MAX_PENDING_USERS = 100;

    private final int threshold;
    private final int chunkSize;
    private final int maxPendingChars;
    private final Duration pendingTtl;
    private final Clock clock;
    private final Map<String, PendingReply> pendingReplies = new ConcurrentHashMap<>();

    public LongReplyManager(int threshold, int chunkSize, int maxPendingChars,
                            Duration pendingTtl) {
        this(threshold, chunkSize, maxPendingChars, pendingTtl, Clock.systemUTC());
    }

    LongReplyManager(int threshold, int chunkSize, int maxPendingChars,
                     Duration pendingTtl, Clock clock) {
        if (threshold < 1) throw new IllegalArgumentException("长回复阈值必须大于 0");
        if (chunkSize < 1) throw new IllegalArgumentException("分段长度必须大于 0");
        if (maxPendingChars < threshold) {
            throw new IllegalArgumentException("暂存字符上限不能小于长回复阈值");
        }
        if (pendingTtl == null || pendingTtl.isZero() || pendingTtl.isNegative()) {
            throw new IllegalArgumentException("长回复暂存时间必须大于 0");
        }
        this.threshold = threshold;
        this.chunkSize = chunkSize;
        this.maxPendingChars = maxPendingChars;
        this.pendingTtl = pendingTtl;
        this.clock = clock;
    }

    public boolean requiresChoice(String reply) {
        return reply != null && reply.length() > threshold;
    }

    /**
     * 暂存完整回复。超过单条暂存上限时返回 false，由调用方直接采用安全降级策略。
     */
    public boolean save(String userId, String reply) {
        if (userId == null || userId.isBlank() || reply == null
            || reply.length() > maxPendingChars) {
            return false;
        }
        removeExpired();
        if (!pendingReplies.containsKey(userId)
            && pendingReplies.size() >= MAX_PENDING_USERS) {
            evictOldest();
        }
        Instant now = clock.instant();
        pendingReplies.put(userId, new PendingReply(
            reply, now, now.plus(pendingTtl)));
        return true;
    }

    public Lookup lookup(String userId) {
        if (userId == null || userId.isBlank()) return Lookup.none();
        PendingReply pending = pendingReplies.get(userId);
        if (pending == null) return Lookup.none();
        if (!clock.instant().isBefore(pending.expiresAt())) {
            pendingReplies.remove(userId, pending);
            return Lookup.expired();
        }
        return Lookup.active(pending.content());
    }

    public void remove(String userId) {
        if (userId != null) pendingReplies.remove(userId);
    }

    public Choice parseChoice(String input) {
        if (input == null) return Choice.UNKNOWN;
        String normalized = input.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，,。.!！?？、：:（）()]+", "");
        if (normalized.equals("1")
            || normalized.equals("第一种")
            || normalized.equals("分段")
            || normalized.equals("分段发送")
            || normalized.equals("文本")
            || normalized.equals("文字")) {
            return Choice.CHUNKS;
        }
        if (normalized.equals("2")
            || normalized.equals("第二种")
            || normalized.equals("文档")
            || normalized.equals("生成文档")
            || normalized.equals("word")
            || normalized.equals("word文档")
            || normalized.equals("docx")) {
            return Choice.DOCUMENT;
        }
        if (normalized.equals("取消")
            || normalized.equals("算了")
            || normalized.equals("不要了")) {
            return Choice.CANCEL;
        }
        return Choice.UNKNOWN;
    }

    /**
     * 尽量在换行或中文句末标点处分段；找不到合适边界时按字符上限切分。
     * 所有片段重新拼接后与原文完全一致。
     */
    public List<String> split(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                end = adjustSurrogateBoundary(text, start, end);
                int naturalBoundary = findNaturalBoundary(text, start, end);
                if (naturalBoundary > start) end = naturalBoundary;
            }
            if (end <= start) end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }

    public String choicePrompt() {
        return "回复内容较长，请选择发送方式：\n"
            + "1. 分段发送\n"
            + "2. 生成 Word 文档\n"
            + "请回复“1”或“2”，也可以回复“取消”。";
    }

    private int findNaturalBoundary(String text, int start, int end) {
        int earliest = start + Math.max(1, chunkSize / 2);
        for (int index = end - 1; index >= earliest; index--) {
            char current = text.charAt(index);
            if (current == '\n' || current == '。' || current == '！'
                || current == '？' || current == '；') {
                return index + 1;
            }
        }
        return end;
    }

    private int adjustSurrogateBoundary(String text, int start, int end) {
        if (end > start && end < text.length()
            && Character.isHighSurrogate(text.charAt(end - 1))
            && Character.isLowSurrogate(text.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    private void removeExpired() {
        Instant now = clock.instant();
        pendingReplies.entrySet().removeIf(
            entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private void evictOldest() {
        pendingReplies.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getValue().createdAt()))
            .ifPresent(entry -> pendingReplies.remove(entry.getKey(), entry.getValue()));
    }

    public enum Choice {
        CHUNKS,
        DOCUMENT,
        CANCEL,
        UNKNOWN
    }

    public enum LookupStatus {
        NONE,
        ACTIVE,
        EXPIRED
    }

    public record Lookup(LookupStatus status, String content) {
        private static Lookup none() {
            return new Lookup(LookupStatus.NONE, "");
        }

        private static Lookup active(String content) {
            return new Lookup(LookupStatus.ACTIVE, content);
        }

        private static Lookup expired() {
            return new Lookup(LookupStatus.EXPIRED, "");
        }
    }

    private record PendingReply(String content, Instant createdAt, Instant expiresAt) {
    }
}
