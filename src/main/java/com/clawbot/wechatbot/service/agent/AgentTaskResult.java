package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 单个外层任务的执行结果。 */
public record AgentTaskResult(
    AgentTask task,
    String text,
    List<AgentAttachment> attachments,
    String error
) {
    public AgentTaskResult {
        if (task == null) throw new IllegalArgumentException("任务不能为空");
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        error = error == null || error.isBlank() ? null : error;
    }

    public static AgentTaskResult success(
        AgentTask task, String text, List<AgentAttachment> attachments
    ) {
        return new AgentTaskResult(task, text, attachments, null);
    }

    public static AgentTaskResult failure(AgentTask task, String error) {
        return new AgentTaskResult(task, "", List.of(), error);
    }

    public boolean succeeded() {
        return error == null;
    }
}
