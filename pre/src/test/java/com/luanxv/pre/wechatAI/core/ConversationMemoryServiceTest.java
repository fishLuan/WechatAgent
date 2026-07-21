package com.luanxv.pre.wechatAI.core;

import com.luanxv.pre.wechatAI.model.ChatMemory;
import com.luanxv.pre.wechatAI.repository.ChatMemoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {
    @Test
    void returnsMongoMemoryInChronologicalOrder() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        ChatMemory newest = new ChatMemory("user-1", "assistant", "second", Instant.parse("2026-07-20T01:00:00Z"));
        ChatMemory oldest = new ChatMemory("user-1", "user", "first", Instant.parse("2026-07-20T00:00:00Z"));
        when(repository.findByUserIdOrderByCreatedAtDesc(eq("user-1"), any())).thenReturn(List.of(newest, oldest));

        List<Map<String, String>> history = new ConversationMemoryService(repository).getHistory("user-1");

        assertEquals("first", history.get(0).get("content"));
        assertEquals("second", history.get(1).get("content"));
    }

    @Test
    void savesEachValidConversationTurn() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        ConversationMemoryService service = new ConversationMemoryService(repository);

        service.addMessage("user-1", "user", "hello");

        verify(repository).save(any(ChatMemory.class));
    }
}
