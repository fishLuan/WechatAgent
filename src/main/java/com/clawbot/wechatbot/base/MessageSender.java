package com.clawbot.wechatbot.base;

public interface MessageSender {
    void sendText(String userId, String text);
    void sendImage(String userId, byte[] imageBytes, String fileName);
    void sendFile(String userId, byte[] fileBytes, String fileName, String caption);
    boolean isReady();
}
