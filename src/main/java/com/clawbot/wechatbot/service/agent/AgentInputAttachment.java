package com.clawbot.wechatbot.service.agent;

/** 用户消息中供 Agent 任务读取的二进制附件。 */
public record AgentInputAttachment(
    AttachmentType type,
    byte[] content,
    String fileName
) {
    public AgentInputAttachment {
        if (type == null) throw new IllegalArgumentException("输入附件类型不能为空");
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("输入附件内容不能为空");
        }
        content = content.clone();
        fileName = fileName == null || fileName.isBlank()
            ? defaultFileName(type)
            : fileName.trim();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    private static String defaultFileName(AttachmentType type) {
        return type == AttachmentType.IMAGE ? "wechat-image.jpg" : "wechat-document";
    }

    public enum AttachmentType {
        IMAGE,
        DOCUMENT
    }
}
