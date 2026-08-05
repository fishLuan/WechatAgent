package com.clawbot.wechatbot.service.agent.reference;

/** 引用格式、来源、权限或路径不合法。 */
public final class ReferenceResolutionException extends RuntimeException {
    private final String code;

    public ReferenceResolutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
