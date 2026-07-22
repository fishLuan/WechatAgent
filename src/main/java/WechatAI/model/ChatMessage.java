package WechatAI.model;

/**
 * 传递给大模型的对话消息，role 与 OpenAI-compatible messages 保持一致。
 */
public class ChatMessage {

    private final String role;
    private final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
