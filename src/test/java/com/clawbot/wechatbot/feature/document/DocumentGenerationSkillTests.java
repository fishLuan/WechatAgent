package com.clawbot.wechatbot.feature.document;

import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentGenerationSkillTests {
    private static final SkillDefinition DEFINITION = definition();

    @Test
    void createsWordFromDependencyResult() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        when(documents.createWord("生成内容", "完整故事正文"))
            .thenReturn(new byte[] {1, 2, 3});

        SkillResult result = new DocumentGenerationSkill(documents).execute(
            DEFINITION,
            new SkillRequest(
                "user", "生成Word文档", "", "", "完整故事正文"));

        assertTrue(result.success());
        assertEquals(1, result.attachments().size());
        assertTrue(result.attachments().getFirst().fileName().endsWith(".docx"));
        verify(documents).createWord("生成内容", "完整故事正文");
    }

    @Test
    void createsPdfFromInlineContent() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        when(documents.createPdf("生成内容", "这是正文"))
            .thenReturn(new byte[] {1});

        SkillResult result = new DocumentGenerationSkill(documents).execute(
            DEFINITION,
            new SkillRequest(
                "user", "生成PDF：这是正文", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.attachments().getFirst().fileName().endsWith(".pdf"));
    }

    @Test
    void extractsTextFieldInsteadOfWritingDependencyJson() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        when(documents.createPdf("生成内容", "杭州今天晴，气温30℃。"))
            .thenReturn(new byte[] {1});

        new DocumentGenerationSkill(documents).execute(
            DEFINITION,
            new SkillRequest("user", "生成PDF文档", "", "",
                "【查询杭州天气】\n{\"weather_text\":\"杭州今天晴，气温30℃。\"}"));

        verify(documents).createPdf("生成内容", "杭州今天晴，气温30℃。");
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
            "document-generation", "1.0.0", true, "文档生成",
            "生成文档", "document-generation",
            List.of(), List.of(), 30, false);
    }
}
