package com.clawbot.wechatbot.feature.document;

import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Converts prepared text into a Word or PDF attachment. */
@Component
public final class DocumentGenerationSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "document-generation";
    private final DocumentService documents;

    public DocumentGenerationSkill(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    public String executorName() {
        return EXECUTOR_NAME;
    }

    @Override
    public SkillResult execute(SkillDefinition definition, SkillRequest request)
        throws Exception {
        if (request == null) {
            return SkillResult.failure("Document generation request cannot be empty");
        }
        String content = resolveContent(request);
        if (content.isBlank()) {
            return SkillResult.failure(
                "No document content is available; generate or provide the text first");
        }
        boolean pdf = requestsPdf(request.instruction());
        String title = resolveTitle(request.instruction());
        byte[] bytes = pdf
            ? documents.createPdf(title, content)
            : documents.createWord(title, content);
        String extension = pdf ? ".pdf" : ".docx";
        AgentAttachment attachment = new AgentAttachment(
            AgentAttachment.AttachmentType.FILE,
            bytes,
            "document-" + System.currentTimeMillis() + extension,
            (pdf ? "PDF" : "Word") + " 文档（" + content.length() + "字）");
        return SkillResult.success(
            "文档已生成：" + attachment.fileName(), List.of(attachment));
    }

    private String resolveContent(SkillRequest request) {
        if (!request.dependencyText().isBlank()) return request.dependencyText();
        String instruction = request.instruction();
        for (String separator : List.of("：", ":")) {
            int index = instruction.indexOf(separator);
            if (index >= 0 && index + separator.length() < instruction.length()) {
                return instruction.substring(index + separator.length()).trim();
            }
        }
        return "";
    }

    private boolean requestsPdf(String instruction) {
        return instruction.toLowerCase(Locale.ROOT).contains("pdf");
    }

    private String resolveTitle(String instruction) {
        String normalized = instruction
            .replaceAll("(?i)pdf|word|docx", "")
            .replaceAll("生成|创建|制作|导出|文档|文件|把|将", "")
            .replaceAll("[：:].*$", "")
            .trim();
        if (normalized.isBlank()) return "生成内容";
        return normalized.substring(0, Math.min(40, normalized.length()));
    }
}
