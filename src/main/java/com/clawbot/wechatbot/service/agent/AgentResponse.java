package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 外层 Agent 循环的统一结果：文字与待发送附件。texts 非空时分条发送。 */
public record AgentResponse(String text, List<String> texts, List<AgentAttachment> attachments) {
    public AgentResponse {
        text = text == null ? "" : text;
        texts = texts == null ? List.of() : List.copyOf(texts);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static AgentResponse text(String text) {
        return new AgentResponse(text, List.of(), List.of());
    }

    public static AgentResponse multi(List<String> texts) {
        return new AgentResponse(texts.isEmpty() ? "" : texts.get(0), texts, List.of());
    }

    /** 是否需要分条发送。 */
    public boolean hasMultipleTexts() {
        return !texts.isEmpty();
    }
}
