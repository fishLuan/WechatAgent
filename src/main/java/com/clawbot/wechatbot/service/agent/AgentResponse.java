package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 外层 Agent 循环的统一结果：文字与待发送附件。 */
public record AgentResponse(String text, List<AgentAttachment> attachments) {
    public AgentResponse {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static AgentResponse text(String text) {
        return new AgentResponse(text, List.of());
    }
}
