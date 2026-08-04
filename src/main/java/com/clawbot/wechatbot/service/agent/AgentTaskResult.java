package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 单个外层任务的执行结果。texts 非空时分条发送（如推荐书籍每条一本）。 */
public record AgentTaskResult(
    AgentTask task,
    String text,
    List<String> texts,
    List<AgentAttachment> attachments,
    String error
) {
    public AgentTaskResult {
        if (task == null) throw new IllegalArgumentException("任务不能为空");
        text = text == null ? "" : text;
        texts = texts == null ? List.of() : List.copyOf(texts);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        error = error == null || error.isBlank() ? null : error;
    }

    public static AgentTaskResult success(
        AgentTask task, String text, List<AgentAttachment> attachments
    ) {
        return new AgentTaskResult(task, text, List.of(), attachments, null);
    }

    public static AgentTaskResult successMulti(
        AgentTask task, List<String> texts
    ) {
        return new AgentTaskResult(task, texts.isEmpty() ? "" : texts.get(0), texts, List.of(), null);
    }

    public static AgentTaskResult failure(AgentTask task, String error) {
        return new AgentTaskResult(task, "", List.of(), List.of(), error);
    }

    public boolean succeeded() {
        return error == null;
    }

    /** 是否需要分条发送。 */
    public boolean hasMultipleTexts() {
        return !texts.isEmpty();
    }
}
