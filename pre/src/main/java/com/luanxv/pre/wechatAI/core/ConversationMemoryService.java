package com.luanxv.pre.wechatAI.core;

import com.luanxv.pre.wechatAI.model.ChatMemory;
import com.luanxv.pre.wechatAI.repository.ChatMemoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MongoDB-backed conversation memory. Only the latest ten turns are sent to Qwen. */
@Service
public class ConversationMemoryService {
    private static final int HISTORY_LIMIT = 10;
    private final ChatMemoryRepository repository;

    public ConversationMemoryService(ChatMemoryRepository repository) {
        this.repository = repository;
    }

    public void addMessage(String userId, String role, String content) {
        if (userId == null || userId.isBlank() || role == null || role.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        try {
            repository.save(new ChatMemory(userId, role, content, Instant.now()));
        } catch (RuntimeException exception) {
            // A temporary MongoDB outage must not stop the bot from replying.
            System.err.println("保存 MongoDB 会话记忆失败: " + exception.getMessage());
        }
    }

    /** Returns turns in chronological order, ready to be appended to a model request. */
    public List<Map<String, String>> getHistory(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            List<ChatMemory> newestFirst = repository.findByUserIdOrderByCreatedAtDesc(
                    userId, PageRequest.of(0, HISTORY_LIMIT));
            List<ChatMemory> chronological = new ArrayList<>(newestFirst);
            Collections.reverse(chronological);
            List<Map<String, String>> history = new ArrayList<>(chronological.size());
            for (ChatMemory memory : chronological) {
                Map<String, String> item = new HashMap<>();
                item.put("role", memory.getRole());
                item.put("content", memory.getContent());
                history.add(item);
            }
            return history;
        } catch (RuntimeException exception) {
            System.err.println("读取 MongoDB 会话记忆失败: " + exception.getMessage());
            return List.of();
        }
    }
}
