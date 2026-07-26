package com.clawbot.wechatbot.memory;

import java.time.Instant;

public record ConversationMessage(String role, String content, Instant createdAt) {
}
