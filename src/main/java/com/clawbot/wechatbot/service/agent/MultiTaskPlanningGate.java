package com.clawbot.wechatbot.service.agent;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;
import java.util.Optional;

/**
 * 位于领域消息处理器之前的统一任务规划入口。
 */
public final class MultiTaskPlanningGate {
    private final TaskPlanner planner;
    private final boolean enabled;

    public MultiTaskPlanningGate(TaskPlanner planner) {
        this(planner, true);
    }

    public MultiTaskPlanningGate(TaskPlanner planner, boolean enabled) {
        this.planner = planner;
        this.enabled = enabled;
    }

    public Optional<List<AgentTask>> plan(WeixinMessage message) {
        return planDetailed(message)
            .filter(plan -> !plan.limitExceeded())
            .map(TaskPlan::tasks);
    }

    public Optional<TaskPlan> planDetailed(WeixinMessage message) {
        if (!enabled || message == null || !planner.isConfigured()) {
            return Optional.empty();
        }
        String text = extractText(message);
        boolean hasAttachment = hasSupportedAttachment(message);
        if (text.isBlank() && !hasAttachment) return Optional.empty();
        String planningInput = buildPlanningInput(message, text, hasAttachment);
        try {
            TaskPlan plan = planner.planDetailed(planningInput);
            return plan == null
                || (!plan.limitExceeded() && plan.tasks().isEmpty())
                ? Optional.empty()
                : Optional.of(plan);
        } catch (Exception error) {
            System.err.println("[WARN] 入口任务规划失败，继续原消息路由："
                + safeMessage(error));
            return Optional.empty();
        }
    }

    public boolean hasSupportedAttachment(WeixinMessage message) {
        if (message == null) return false;
        if (message.getItem_list() == null) return false;
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            if (item.getImage_item() != null) return true;
            if (item.getFile_item() != null
                && isSupportedDocument(item.getFile_item().getFile_name())) return true;
        }
        return false;
    }

    private String buildPlanningInput(
        WeixinMessage message,
        String text,
        boolean hasAttachment
    ) {
        if (!hasAttachment) return text;
        StringBuilder input = new StringBuilder();
        input.append("【用户要求】\n")
            .append(text.isBlank() ? defaultAttachmentInstruction(message) : text)
            .append("\n\n【附件】\n");
        int imageIndex = 0;
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            if (item.getImage_item() != null) {
                input.append("- 图片 ").append(++imageIndex).append('\n');
            } else if (item.getFile_item() != null
                && isSupportedDocument(item.getFile_item().getFile_name())) {
                input.append("- 文档：")
                    .append(item.getFile_item().getFile_name())
                    .append('\n');
            }
        }
        return input.toString().trim();
    }

    private String defaultAttachmentInstruction(WeixinMessage message) {
        boolean image = false;
        boolean document = false;
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            image |= item.getImage_item() != null;
            document |= item.getFile_item() != null
                && isSupportedDocument(item.getFile_item().getFile_name());
        }
        if (image && document) return "分别分析我上传的图片和文档";
        if (image) return "描述并分析我上传的图片";
        return "总结我上传的文档";
    }

    private boolean isSupportedDocument(String fileName) {
        if (fileName == null) return false;
        String normalized = fileName.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".pdf")
            || normalized.endsWith(".doc")
            || normalized.endsWith(".docx")
            || normalized.endsWith(".txt");
    }

    private String extractText(WeixinMessage message) {
        if (message.getItem_list() == null) return "";
        StringBuilder text = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            if (item.getType() == 1 && item.getText_item() != null) {
                text.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem voice = item.getVoice_item();
                if (voice.getText() != null) text.append(voice.getText());
            }
        }
        return text.toString().trim();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }
}
