package com.clawbot.wechatbot.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConversationMemoryServiceTests {
    private MemoryKeyFactory keys;
    private InMemoryConversationMemoryService service;

    @BeforeEach
    void setUp() {
        MemoryProperties properties = new MemoryProperties();
        properties.setNamespace("test-bot");
        properties.setRecentTurns(2);
        properties.setSummaryEvery(2);
        properties.setMessageDedupTtlMinutes(30);
        keys = new MemoryKeyFactory(properties);
        service = new InMemoryConversationMemoryService(properties, keys);
    }

    @Test
    void keepsConversationAndSummaryIsolatedByUser() {
        service.appendTurn("user-a", "A的问题", "A的回答");
        service.replaceSummary("user-a", "A的长期摘要");
        service.appendTurn("user-b", "B的问题", "B的回答");
        service.replaceSummary("user-b", "B的长期摘要");

        ConversationMemory memoryA = service.get("user-a");
        ConversationMemory memoryB = service.get("user-b");

        assertEquals("A的长期摘要", memoryA.getLongTermSummary());
        assertEquals("B的长期摘要", memoryB.getLongTermSummary());
        assertEquals("A的问题", memoryA.getRecentMessages().get(0).content());
        assertEquals("B的问题", memoryB.getRecentMessages().get(0).content());
        assertNotEquals(memoryA.getId(), memoryB.getId());
        assertFalse(memoryA.getId().contains("user-a"));
    }

    @Test
    void clearOnlyRemovesTheCurrentUsersMemoryAndDeduplicationState() {
        service.appendTurn("user-a", "A", "answer-a");
        service.appendTurn("user-b", "B", "answer-b");
        assertTrue(service.markMessageProcessed("user-a", 100L));
        assertTrue(service.markMessageProcessed("user-b", 100L));

        service.clear("user-a");

        assertTrue(service.get("user-a").getRecentMessages().isEmpty());
        assertEquals(2, service.get("user-b").getRecentMessages().size());
        assertTrue(service.markMessageProcessed("user-a", 100L));
        assertFalse(service.markMessageProcessed("user-b", 100L));
    }

    @Test
    void keepsOnlyTheConfiguredNumberOfRecentTurns() {
        service.appendTurn("user-a", "question-1", "answer-1");
        service.appendTurn("user-a", "question-2", "answer-2");
        service.appendTurn("user-a", "question-3", "answer-3");

        ConversationMemory memory = service.get("user-a");

        assertEquals(3, memory.getTurnCounter());
        assertEquals(4, memory.getRecentMessages().size());
        assertEquals("question-2", memory.getRecentMessages().get(0).content());
        assertEquals("answer-3", memory.getRecentMessages().get(3).content());
    }

    @Test
    void deduplicatesTheSameMessageOnlyWithinTheSameUser() {
        assertTrue(service.markMessageProcessed("user-a", 900L));
        assertFalse(service.markMessageProcessed("user-a", 900L));
        assertTrue(service.markMessageProcessed("user-b", 900L));
    }
}
