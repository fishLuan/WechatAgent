package com.luanxv.pre.wechatAI.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One persisted conversation turn. */
@Document("chat_memories")
@CompoundIndex(name = "user_created_at_idx", def = "{'userId': 1, 'createdAt': -1}")
public class ChatMemory {
    @Id
    private String id;
    private String userId;
    private String role;
    private String content;
    private Instant createdAt;

    public ChatMemory(String userId, String role, String content, Instant createdAt) {
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
