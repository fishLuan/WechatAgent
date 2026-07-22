package WechatAI.service;

import WechatAI.model.ChatMessage;

import java.util.List;

/**
 * 短期记忆服务抽象，屏蔽 Redis 等存储实现细节。
 */
public interface MemoryService {

    List<ChatMessage> loadRecentMessages(String userId);

    void appendConversation(String userId, String userMessage, String assistantMessage);

    void clear(String userId);
}
