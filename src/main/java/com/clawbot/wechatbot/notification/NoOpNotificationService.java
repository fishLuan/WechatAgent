package com.clawbot.wechatbot.notification;

/** Notification implementation used when DingTalk is disabled or unconfigured. */
public final class NoOpNotificationService implements NotificationService {
    @Override
    public void notifyLoginRequired(String loginContent) {
    }

    @Override
    public void notifyLoginSuccess(String botId, String userId) {
    }

    @Override
    public void notifyError(String source, Throwable error) {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
