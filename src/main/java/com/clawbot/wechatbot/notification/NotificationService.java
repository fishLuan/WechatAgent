package com.clawbot.wechatbot.notification;

import org.springframework.stereotype.Component;

/**
 * Application event notifications. This infrastructure service is deliberately
 * not exposed to the LLM function-calling registry.
 */
@Component
public interface NotificationService extends AutoCloseable {
    void notifyLoginRequired(String loginContent);

    void notifyLoginSuccess(String botId, String userId);

    void notifyError(String source, Throwable error);

    boolean isEnabled();

    @Override
    default void close() {
    }
}
