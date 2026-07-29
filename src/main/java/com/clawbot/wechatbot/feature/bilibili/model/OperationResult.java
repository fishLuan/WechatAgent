package com.clawbot.wechatbot.feature.bilibili.model;

/** 暂停、恢复、取消等通用操作结果。 */
public record OperationResult(boolean success, String message) {
    public static OperationResult succeeded(String message) {
        return new OperationResult(true, message);
    }

    public static OperationResult failed(String message) {
        return new OperationResult(false, message);
    }
}
