package com.clawbot.wechatbot.feature.bilibili.source;

/** B 站公开接口触发风控或访问限制。 */
public class BilibiliAccessLimitedException extends RuntimeException {
    public BilibiliAccessLimitedException(String message) {
        super(message);
    }
}
