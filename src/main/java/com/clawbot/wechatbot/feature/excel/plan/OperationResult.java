package com.clawbot.wechatbot.feature.excel.plan;

/** 单个操作执行的结果：文字回复 + 可空的 xlsx 附件（查询/版本历史等纯文字操作无附件）。 */
public record OperationResult(boolean success, String text, byte[] attachment) {

    public static OperationResult success(String text) {
        return new OperationResult(true, text, null);
    }

    public static OperationResult success(String text, byte[] attachment) {
        return new OperationResult(true, text, attachment);
    }

    public static OperationResult failure(String text) {
        return new OperationResult(false, text, null);
    }
}
