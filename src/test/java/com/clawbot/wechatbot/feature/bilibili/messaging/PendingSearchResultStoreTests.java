package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingSearchResultStoreTests {

    @Test
    void isolatesResultsByWechatUser() {
        PendingSearchResultStore store = new PendingSearchResultStore();
        BilibiliContent result =
            new BilibiliContent(ContentType.BANGUMI, "media-3", "第三部");

        store.put("user-a", List.of(result));

        assertEquals(result, store.findByItemNumber("user-a", 1));
        assertNull(store.findByItemNumber("user-b", 1));
    }

    @Test
    void expiresOldSearchResults() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-30T00:00:00Z"));
        PendingSearchResultStore store =
            new PendingSearchResultStore(clock, Duration.ofHours(2));
        store.put(
            "user-a",
            List.of(new BilibiliContent(
                ContentType.BANGUMI, "media-3", "第三部")));

        clock.advance(Duration.ofHours(2));

        assertNull(store.findByItemNumber("user-a", 1));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
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
            return current;
        }
    }
}
