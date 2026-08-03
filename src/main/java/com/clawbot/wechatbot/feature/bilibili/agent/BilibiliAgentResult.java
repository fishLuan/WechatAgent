package com.clawbot.wechatbot.feature.bilibili.agent;

import java.util.List;

/** B站子Agent对Skill层返回的聚合结果。 */
public record BilibiliAgentResult(
    boolean success,
    String text,
    List<BilibiliTaskResult> taskResults
) {
    public BilibiliAgentResult {
        text = text == null ? "" : text.trim();
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
    }

    public static BilibiliAgentResult failure(String text) {
        return new BilibiliAgentResult(false, text, List.of());
    }
}
