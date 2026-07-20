package WechatAI.service;

import WechatAI.model.ChatMessage;

import java.util.List;

public interface AiChatService {

    String chat(String userMessage);

    String chat(String userMessage, List<ChatMessage> historyMessages);
}
