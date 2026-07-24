package com.clawbot.wechatbot.notification;

/**
 * Application event notifications. This infrastructure service is deliberately
 * not exposed to the LLM function-calling registry.
 */
public interface NotificationService extends AutoCloseable {
    void notifyLoginRequired(String loginContent);

    void notifyLoginSuccess(String botId, String userId);

    void notifyError(String source, Throwable error);

    boolean isEnabled();

    @Override
    default void close() {
    }
}
