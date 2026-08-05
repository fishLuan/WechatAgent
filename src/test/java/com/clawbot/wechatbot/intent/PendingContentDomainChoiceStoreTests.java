package com.clawbot.wechatbot.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingContentDomainChoiceStoreTests {
    @Test
    void choicesAreIsolatedByUserAndConsumedOnce() {
        PendingContentDomainChoiceStore store = new PendingContentDomainChoiceStore();
        store.put("user-a", "三体");
        store.put("user-b", "活着");

        assertEquals("三体", store.consume("user-a").title());
        assertNull(store.get("user-a"));
        assertEquals("活着", store.get("user-b").title());
    }
}
