package com.clawbot.wechatbot.feature.bilibili.source.client;

/** Indicates that Bilibili rejected a request because of access control or rate limiting. */
public final class BilibiliAccessLimitedException extends IllegalStateException {
    public BilibiliAccessLimitedException(String message) {
        super(message);
    }

    public BilibiliAccessLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}
