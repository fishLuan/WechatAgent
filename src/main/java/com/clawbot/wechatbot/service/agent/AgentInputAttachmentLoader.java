package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.DocumentService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.ArrayList;
import java.util.List;

/** 下载并校验一次微信消息中供 Agent 使用的图片和文档。 */
public final class AgentInputAttachmentLoader {
    private final DocumentService documentService;
    private final int maxAttachments;
    private final long maxSingleBytes;
    private final long maxTotalBytes;

    public AgentInputAttachmentLoader(
        DocumentService documentService,
        int maxAttachments,
        long maxSingleBytes,
        long maxTotalBytes
    ) {
        this.documentService = documentService;
        this.maxAttachments = requirePositive(maxAttachments, "附件数量上限");
        this.maxSingleBytes = requirePositive(maxSingleBytes, "单个附件大小上限");
        this.maxTotalBytes = requirePositive(maxTotalBytes, "附件累计大小上限");
        if (maxSingleBytes > maxTotalBytes) {
            throw new IllegalArgumentException("单个附件大小上限不能超过累计大小上限");
        }
    }

    public List<AgentInputAttachment> load(
        ILinkClient client,
        WeixinMessage message
    ) throws Exception {
        if (client == null || message == null || message.getItem_list() == null) {
            return List.of();
        }
        List<AgentInputAttachment> attachments = new ArrayList<>();
        long totalBytes = 0;
        int imageIndex = 0;

        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            AgentInputAttachment.AttachmentType type;
            String fileName;
            long declaredSize;
            if (item.getImage_item() != null) {
                type = AgentInputAttachment.AttachmentType.IMAGE;
                fileName = "wechat-image-" + (++imageIndex) + ".jpg";
                declaredSize = imageDeclaredSize(item.getImage_item());
            } else if (isSupportedDocument(item.getFile_item())) {
                type = AgentInputAttachment.AttachmentType.DOCUMENT;
                fileName = item.getFile_item().getFile_name();
                declaredSize = parseSize(item.getFile_item().getLen());
            } else {
                continue;
            }

            if (attachments.size() >= maxAttachments) {
                throw new IllegalArgumentException(
                    "附件数量超过限制，最多支持 " + maxAttachments + " 个");
            }
            validateDeclaredSize(declaredSize, totalBytes);

            byte[] content = type == AgentInputAttachment.AttachmentType.IMAGE
                ? client.downloadImageFromMessageItem(item)
                : client.downloadFileFromMessageItem(item);
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("附件下载失败：" + fileName);
            }
            validateActualSize(content.length, totalBytes);
            totalBytes += content.length;
            attachments.add(new AgentInputAttachment(type, content, fileName));
        }
        return List.copyOf(attachments);
    }

    private boolean isSupportedDocument(FileItem file) {
        if (file == null) return false;
        String fileName = file.getFile_name();
        return documentService.isPdf(fileName)
            || documentService.isWord(fileName)
            || documentService.isText(fileName);
    }

    private long imageDeclaredSize(ImageItem image) {
        if (image.getHd_size() != null && image.getHd_size() > 0) {
            return image.getHd_size();
        }
        return image.getMid_size() == null ? 0 : image.getMid_size();
    }

    private long parseSize(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void validateDeclaredSize(long size, long currentTotal) {
        if (size <= 0) return;
        validateSize(size, currentTotal);
    }

    private void validateActualSize(long size, long currentTotal) {
        validateSize(size, currentTotal);
    }

    private void validateSize(long size, long currentTotal) {
        if (size > maxSingleBytes) {
            throw new IllegalArgumentException(
                "单个附件超过大小限制 " + formatMegabytes(maxSingleBytes));
        }
        if (currentTotal + size > maxTotalBytes) {
            throw new IllegalArgumentException(
                "附件累计大小超过限制 " + formatMegabytes(maxTotalBytes));
        }
    }

    private String formatMegabytes(long bytes) {
        return Math.max(1, bytes / 1024 / 1024) + "MB";
    }

    private int requirePositive(int value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + "必须大于0");
        return value;
    }

    private long requirePositive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + "必须大于0");
        return value;
    }
}
