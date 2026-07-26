package com.clawbot.wechatbot.memory;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class MemoryKeyFactory {
    private final MemoryProperties properties;

    public MemoryKeyFactory(MemoryProperties properties) {
        this.properties = properties;
    }

    public String namespace() { return properties.getNamespace(); }

    public String userKey(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return sha256(namespace() + "\n" + userId);
    }

    public String memoryId(String userId) {
        return namespace() + ":" + userKey(userId);
    }

    public String deduplicationId(String userId, Long messageId) {
        if (messageId == null) throw new IllegalArgumentException("messageId must not be null");
        return memoryId(userId) + ":" + messageId;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
