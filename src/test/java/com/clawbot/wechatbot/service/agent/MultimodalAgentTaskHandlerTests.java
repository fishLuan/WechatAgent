package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.VisionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultimodalAgentTaskHandlerTests {

    @Test
    void understandsUploadedImage() throws Exception {
        VisionService vision = mock(VisionService.class);
        byte[] image = {1, 2, 3};
        when(vision.isConfigured()).thenReturn(true);
        when(vision.understandImage(any(byte[].class), eq("识别图片中的动物")))
            .thenReturn("图片中是一只猫");
        ImageUnderstandingAgentTaskHandler handler =
            new ImageUnderstandingAgentTaskHandler(vision);
        AgentTask task = new AgentTask(
            "image", 0, AgentTaskType.IMAGE_UNDERSTANDING,
            "识别图片中的动物", List.of());

        AgentTaskResult result = handler.execute(
            task,
            context(new AgentInputAttachment(
                AgentInputAttachment.AttachmentType.IMAGE,
                image,
                "cat.jpg")));

        assertTrue(result.succeeded());
        assertEquals("图片中是一只猫", result.text());
    }

    @Test
    void extractsDocumentAndAppliesCharacterLimit() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        byte[] bytes = "document".getBytes(StandardCharsets.UTF_8);
        when(documents.extractText(any(byte[].class), eq("todo.txt")))
            .thenReturn("1234567890");
        AtomicReference<String> prompt = new AtomicReference<>();
        ChatService chat = new ChatService() {
            @Override
            public String chat(String userText, String history) {
                prompt.set(userText);
                return "总结完成";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        DocumentAnalysisAgentTaskHandler handler =
            new DocumentAnalysisAgentTaskHandler(documents, chat, 5);
        AgentTask task = new AgentTask(
            "document", 0, AgentTaskType.DOCUMENT_ANALYSIS,
            "提取待办事项", List.of());

        AgentTaskResult result = handler.execute(
            task,
            context(new AgentInputAttachment(
                AgentInputAttachment.AttachmentType.DOCUMENT,
                bytes,
                "todo.txt")));

        assertTrue(result.succeeded());
        assertEquals("总结完成", result.text());
        assertTrue(prompt.get().contains("12345"));
        assertTrue(prompt.get().contains("只读取前 5 字"));
    }

    private AgentTaskContext context(AgentInputAttachment attachment) {
        return new AgentTaskContext("", "", Map.of(), List.of(attachment));
    }
}
