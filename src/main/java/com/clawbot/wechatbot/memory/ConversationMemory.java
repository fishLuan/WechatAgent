package com.clawbot.wechatbot.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "conversation_memory")
@CompoundIndex(
    name = "uk_conversation_memory_namespace_user",
    def = "{'namespace': 1, 'userKey': 1}",
    unique = true
)
public class ConversationMemory {
    @Id
    private String id;
    private String namespace;
    private String userKey;
    private String longTermSummary = "";
    private List<ConversationMessage> recentMessages = new ArrayList<>();
    private int turnCounter;
    private Instant createdAt;
    private Instant updatedAt;

    public static ConversationMemory empty(String id, String namespace, String userKey) {
        ConversationMemory memory = new ConversationMemory();
        memory.id = id;
        memory.namespace = namespace;
        memory.userKey = userKey;
        return memory;
    }

    public ConversationMemory copy() {
        ConversationMemory copy = empty(id, namespace, userKey);
        copy.longTermSummary = longTermSummary;
        copy.recentMessages = new ArrayList<>(getRecentMessages());
        copy.turnCounter = turnCounter;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getUserKey() { return userKey; }
    public void setUserKey(String userKey) { this.userKey = userKey; }
    public String getLongTermSummary() {
        return longTermSummary == null ? "" : longTermSummary;
    }
    public void setLongTermSummary(String value) {
        this.longTermSummary = value == null ? "" : value;
    }
    public List<ConversationMessage> getRecentMessages() {
        if (recentMessages == null) recentMessages = new ArrayList<>();
        return recentMessages;
    }
    public void setRecentMessages(List<ConversationMessage> value) {
        this.recentMessages = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public int getTurnCounter() { return turnCounter; }
    public void setTurnCounter(int turnCounter) { this.turnCounter = turnCounter; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
