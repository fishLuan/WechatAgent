package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.VisionService;

import java.util.List;

/** 使用用户上传的图片执行图片理解任务。 */
public final class ImageUnderstandingAgentTaskHandler implements AgentTaskHandler {
    private final VisionService visionService;

    public ImageUnderstandingAgentTaskHandler(VisionService visionService) {
        this.visionService = visionService;
    }

    @Override
    public boolean supports(AgentTaskType type) {
        return type == AgentTaskType.IMAGE_UNDERSTANDING;
    }

    @Override
    public AgentTaskResult execute(
        AgentTask task,
        AgentTaskContext context
    ) throws Exception {
        if (!visionService.isConfigured()) {
            return AgentTaskResult.failure(
                task, "图片理解服务未配置，请先配置 DASHSCOPE_API_KEY");
        }
        List<AgentInputAttachment> images = context.inputAttachments().stream()
            .filter(attachment ->
                attachment.type() == AgentInputAttachment.AttachmentType.IMAGE)
            .toList();
        if (images.isEmpty()) {
            return AgentTaskResult.failure(task, "没有找到可供理解的图片附件");
        }
        String question = task.instruction();
        String dependencyText = context.dependencyText();
        if (!dependencyText.isBlank()) {
            question += "\n\n请结合以下前置任务结果：\n" + dependencyText;
        }
        StringBuilder combined = new StringBuilder();
        for (int index = 0; index < images.size(); index++) {
            String answer = visionService.understandImage(
                images.get(index).content(), question);
            if (images.size() > 1) {
                if (combined.length() > 0) combined.append("\n\n");
                combined.append("【图片 ").append(index + 1).append("】\n");
            }
            combined.append(answer);
        }
        return AgentTaskResult.success(task, combined.toString(), List.of());
    }
}
