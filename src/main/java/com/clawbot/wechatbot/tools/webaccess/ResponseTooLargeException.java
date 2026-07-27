package com.clawbot.wechatbot.tools.webaccess;

import java.io.IOException;

public class ResponseTooLargeException extends IOException {
    public ResponseTooLargeException(long maxBytes) {
        super("网页响应超过系统限制：" + maxBytes + " 字节");
    }
}
