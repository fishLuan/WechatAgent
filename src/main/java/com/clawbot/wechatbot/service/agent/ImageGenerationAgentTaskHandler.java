package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ImageGenService;

import java.util.List;

/** 执行文生图任务，图片作为附件返回给统一发送层。 */
public final class ImageGenerationAgentTaskHandler implements AgentTaskHandler {
    private final ImageGenService imageGenService;

    public ImageGenerationAgentTaskHandler(ImageGenService imageGenService) {
        this.imageGenService = imageGenService;
    }

    @Override
    public boolean supports(AgentTaskType type) {
        return type == AgentTaskType.IMAGE_GENERATION;
    }

    @Override
    public AgentTaskResult execute(AgentTask task, AgentTaskContext context) throws Exception {
        if (!imageGenService.isConfigured()) {
            return AgentTaskResult.failure(
                task, "图片生成服务未配置，请先配置 DASHSCOPE_API_KEY");
        }
        String prompt = task.instruction();
        String dependencyText = context.dependencyText();
        if (!dependencyText.isBlank()) {
            prompt += "\n\n请根据以下前置结果调整画面内容和氛围：\n" + dependencyText;
        }
        byte[] image = imageGenService.generateImage(prompt);
        String fileName = "ai-generated-" + System.currentTimeMillis()
            + "-" + task.order() + ".png";
        AgentAttachment attachment = new AgentAttachment(
            AgentAttachment.AttachmentType.IMAGE,
            image,
            fileName,
            "🎨 " + compactCaption(task.instruction()));
        return AgentTaskResult.success(task, "图片已生成。", List.of(attachment));
    }

    private String compactCaption(String instruction) {
        String caption = instruction.replaceAll("\\s+", " ").trim();
        return caption.length() <= 80 ? caption : caption.substring(0, 80) + "…";
    }
}
