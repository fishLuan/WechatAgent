package com.clawbot.wechatbot.skills;

import com.clawbot.wechatbot.service.agent.AgentAttachment;

import java.util.List;

/** Skill执行结果，可同时返回文字和Agent附件。 */
public record SkillResult(
    boolean success,
    String text,
    List<AgentAttachment> attachments
) {
    public SkillResult {
        text = text == null ? "" : text.trim();
        attachments = attachments == null
            ? List.of()
            : List.copyOf(attachments);
    }

    public static SkillResult success(String text) {
        return new SkillResult(true, text, List.of());
    }

    public static SkillResult success(
        String text,
        List<AgentAttachment> attachments
    ) {
        return new SkillResult(true, text, attachments);
    }

    public static SkillResult failure(String message) {
        return new SkillResult(false, message, List.of());
    }
}
