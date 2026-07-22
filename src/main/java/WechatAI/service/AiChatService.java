package WechatAI.service;

import WechatAI.model.ChatMessage;

import java.util.List;

/**
 * 文本对话服务抽象，负责把用户消息和短期记忆交给底层大模型。
 */
public interface AiChatService {

    String chat(String userMessage);

    String chat(String userMessage, List<ChatMessage> historyMessages);
}
