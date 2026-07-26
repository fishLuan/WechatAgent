package com.clawbot.wechatbot.memory;

public interface ConversationMemoryService {
    ConversationMemory get(String userId);
    ConversationMemory appendTurn(String userId, String userText, String assistantReply);
    ConversationMemory replaceSummary(String userId, String summary);
    boolean markMessageProcessed(String userId, Long messageId);
    void clear(String userId);
}
