package com.clawbot.wechatbot.feature.bilibili.agent;

/** 单个B站领域任务的执行结果。 */
public record BilibiliTaskResult(
    BilibiliTask task,
    boolean success,
    String text
) {
    public BilibiliTaskResult {
        text = text == null ? "" : text.trim();
    }

    public static BilibiliTaskResult success(BilibiliTask task, String text) {
        return new BilibiliTaskResult(task, true, text);
    }

    public static BilibiliTaskResult failure(BilibiliTask task, String text) {
        return new BilibiliTaskResult(task, false, text);
    }
}
