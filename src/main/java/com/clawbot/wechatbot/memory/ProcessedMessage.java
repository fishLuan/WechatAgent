package com.clawbot.wechatbot.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "processed_message")
public class ProcessedMessage {
    @Id
    private String id;
    private String namespace;
    private String userKey;
    private Long messageId;
    private Instant expireAt;

    public ProcessedMessage(
        String id, String namespace, String userKey, Long messageId, Instant expireAt
    ) {
        this.id = id;
        this.namespace = namespace;
        this.userKey = userKey;
        this.messageId = messageId;
        this.expireAt = expireAt;
    }

    public String getId() { return id; }
    public String getNamespace() { return namespace; }
    public String getUserKey() { return userKey; }
    public Long getMessageId() { return messageId; }
    public Instant getExpireAt() { return expireAt; }
}
