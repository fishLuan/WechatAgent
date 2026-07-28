package com.clawbot.wechatbot.service.agent;

/** Agent 执行期间产生、最终由消息层发送的二进制附件。 */
public record AgentAttachment(
    AttachmentType type,
    byte[] content,
    String fileName,
    String caption
) {
    public AgentAttachment {
        if (type == null) throw new IllegalArgumentException("附件类型不能为空");
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("附件内容不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("附件文件名不能为空");
        }
        content = content.clone();
        caption = caption == null ? "" : caption;
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public enum AttachmentType {
        IMAGE,
        FILE
    }
}
