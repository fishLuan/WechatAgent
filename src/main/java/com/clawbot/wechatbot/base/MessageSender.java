package com.clawbot.wechatbot.base;

public interface MessageSender {
    void sendText(String userId, String text);
    void sendImage(String userId, byte[] imageBytes, String fileName);
    void sendFile(String userId, byte[] fileBytes, String fileName, String caption);
    boolean isReady();

    /** 当前进程是否已为指定用户建立可用于主动发送的会话上下文。 */
    default boolean isReadyFor(String userId) {
        return isReady();
    }
}
