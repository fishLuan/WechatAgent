package com.clawbot.wechatbot.notification;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Last-resort notification for exceptions that terminate an application thread. */
@Component
public final class UncaughtExceptionNotificationHandler {
    private final NotificationService notifications;
    private Thread.UncaughtExceptionHandler previousHandler;

    public UncaughtExceptionNotificationHandler(NotificationService notifications) {
        this.notifications = notifications;
    }

    @PostConstruct
    void install() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            notifications.notifyError("未捕获异常/" + thread.getName(), error);
            if (previousHandler != null) previousHandler.uncaughtException(thread, error);
        });
    }

    @PreDestroy
    void restore() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
}
