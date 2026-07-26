package com.clawbot.wechatbot.tools.webaccess;

public class UnsafeUrlException extends Exception {
    public UnsafeUrlException(String message) {
        super(message);
    }

    public UnsafeUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
