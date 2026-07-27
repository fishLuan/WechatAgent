package com.clawbot.wechatbot.service.reply;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongReplyManagerTests {

    @Test
    void onlyRepliesAboveThresholdNeedAChoice() {
        LongReplyManager manager = manager();

        assertFalse(manager.requiresChoice("1234567890"));
        assertTrue(manager.requiresChoice("12345678901"));
    }

    @Test
    void isolatesPendingRepliesByUser() {
        LongReplyManager manager = manager();

        assertTrue(manager.save("user-a", "用户 A 的完整回复"));
        assertTrue(manager.save("user-b", "用户 B 的完整回复"));

        assertEquals("用户 A 的完整回复", manager.lookup("user-a").content());
        assertEquals("用户 B 的完整回复", manager.lookup("user-b").content());

        manager.remove("user-a");
        assertEquals(
            LongReplyManager.LookupStatus.NONE,
            manager.lookup("user-a").status());
        assertEquals(
            LongReplyManager.LookupStatus.ACTIVE,
            manager.lookup("user-b").status());
    }

    @Test
    void expiresPendingReplyWithoutAffectingOtherUsers() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-27T00:00:00Z"));
        LongReplyManager manager = new LongReplyManager(
            10, 8, 100, Duration.ofMinutes(10), clock);
        manager.save("user-a", "待发送回复");
        clock.advance(Duration.ofMinutes(11));

        assertEquals(
            LongReplyManager.LookupStatus.EXPIRED,
            manager.lookup("user-a").status());
        assertEquals(
            LongReplyManager.LookupStatus.NONE,
            manager.lookup("user-a").status());
    }

    @Test
    void understandsSupportedReplyChoices() {
        LongReplyManager manager = manager();

        assertEquals(LongReplyManager.Choice.CHUNKS, manager.parseChoice("1"));
        assertEquals(LongReplyManager.Choice.CHUNKS, manager.parseChoice("分段发送"));
        assertEquals(LongReplyManager.Choice.DOCUMENT, manager.parseChoice("2"));
        assertEquals(LongReplyManager.Choice.DOCUMENT, manager.parseChoice("Word 文档"));
        assertEquals(LongReplyManager.Choice.CANCEL, manager.parseChoice("取消"));
        assertEquals(LongReplyManager.Choice.UNKNOWN, manager.parseChoice("重新回答"));
    }

    @Test
    void splitsAtNaturalBoundariesWithoutLosingContent() {
        LongReplyManager manager = new LongReplyManager(
            10, 8, 100, Duration.ofMinutes(10));
        String original = "第一句话。第二句话！第三段内容";

        List<String> chunks = manager.split(original);

        assertTrue(chunks.size() > 1);
        assertEquals(original, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 8));
    }

    @Test
    void doesNotSplitAnEmojiSurrogatePair() {
        LongReplyManager manager = new LongReplyManager(
            3, 3, 100, Duration.ofMinutes(10));
        String original = "ab😀cd";

        List<String> chunks = manager.split(original);

        assertEquals(original, String.join("", chunks));
        assertFalse(chunks.stream().anyMatch(
            chunk -> Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))));
        assertFalse(chunks.stream().anyMatch(
            chunk -> Character.isLowSurrogate(chunk.charAt(0))));
    }

    @Test
    void rejectsRepliesAboveThePendingMemoryLimit() {
        LongReplyManager manager = manager();

        assertFalse(manager.save("user-a", "x".repeat(101)));
        assertEquals(
            LongReplyManager.LookupStatus.NONE,
            manager.lookup("user-a").status());
    }

    private LongReplyManager manager() {
        return new LongReplyManager(10, 8, 100, Duration.ofMinutes(10));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
