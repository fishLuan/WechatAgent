package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;

import java.util.List;

/** 提取用户上传文档的文本，再交给对话模型完成分析。 */
public final class DocumentAnalysisAgentTaskHandler implements AgentTaskHandler {
    private final DocumentService documentService;
    private final ChatService chatService;
    private final int maxDocumentChars;

    public DocumentAnalysisAgentTaskHandler(
        DocumentService documentService,
        ChatService chatService,
        int maxDocumentChars
    ) {
        if (maxDocumentChars <= 0) {
            throw new IllegalArgumentException("文档输入字符上限必须大于0");
        }
        this.documentService = documentService;
        this.chatService = chatService;
        this.maxDocumentChars = maxDocumentChars;
    }

    @Override
    public boolean supports(AgentTaskType type) {
        return type == AgentTaskType.DOCUMENT_ANALYSIS;
    }

    @Override
    public AgentTaskResult execute(
        AgentTask task,
        AgentTaskContext context
    ) throws Exception {
        List<AgentInputAttachment> documents = context.inputAttachments().stream()
            .filter(attachment ->
                attachment.type() == AgentInputAttachment.AttachmentType.DOCUMENT)
            .toList();
        if (documents.isEmpty()) {
            return AgentTaskResult.failure(task, "没有找到可供分析的文档附件");
        }

        StringBuilder extracted = new StringBuilder();
        boolean truncated = false;
        int contentChars = 0;
        for (AgentInputAttachment document : documents) {
            String content = documentService.extractText(
                document.content(), document.fileName());
            if (content == null || content.isBlank()) continue;
            int remaining = maxDocumentChars - contentChars;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (extracted.length() > 0) extracted.append("\n\n");
            extracted.append("【").append(document.fileName()).append("】\n");
            if (content.length() > remaining) {
                extracted.append(content, 0, remaining);
                contentChars += remaining;
                truncated = true;
                break;
            }
            extracted.append(content);
            contentChars += content.length();
        }
        if (extracted.isEmpty()) {
            return AgentTaskResult.failure(task, "文档为空或无法提取文本");
        }
        StringBuilder prompt = new StringBuilder()
            .append("请完成以下文档任务：\n")
            .append(task.instruction())
            .append("\n\n文档内容：\n").append(extracted);
        if (truncated) {
            prompt.append("\n\n（文档较长，本次只读取前 ")
                .append(maxDocumentChars).append(" 字）");
        }
        String dependencyText = context.dependencyText();
        if (!dependencyText.isBlank()) {
            prompt.append("\n\n前置任务结果：\n").append(dependencyText);
        }
        String answer = chatService.chat(prompt.toString(), context.history());
        return AgentTaskResult.success(task, answer, List.of());
    }
}
