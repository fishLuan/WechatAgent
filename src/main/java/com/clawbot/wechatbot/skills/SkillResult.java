package com.clawbot.wechatbot.skills;

import com.clawbot.wechatbot.service.agent.AgentAttachment;

import java.util.List;

/** Skill执行结果，可同时返回文字和Agent附件。texts 非空时分条发送。 */
public record SkillResult(
    boolean success,
    String text,
    List<String> texts,
    List<AgentAttachment> attachments
) {
    public SkillResult {
        text = text == null ? "" : text.trim();
        texts = texts == null ? List.of() : List.copyOf(texts);
        attachments = attachments == null
            ? List.of()
            : List.copyOf(attachments);
    }

    public static SkillResult success(String text) {
        return new SkillResult(true, text, List.of(), List.of());
    }

    public static SkillResult successMulti(List<String> texts) {
        return new SkillResult(true, texts.isEmpty() ? "" : texts.get(0), texts, List.of());
    }

    public static SkillResult success(
        String text,
        List<AgentAttachment> attachments
    ) {
        return new SkillResult(true, text, List.of(), attachments);
    }

    public static SkillResult failure(String message) {
        return new SkillResult(false, message, List.of(), List.of());
    }

    /** 是否需要分条发送。 */
    public boolean hasMultipleTexts() {
        return !texts.isEmpty();
    }
}
