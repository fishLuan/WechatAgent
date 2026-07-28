package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.ImageGenService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTests {

    @Test
    void executesWeatherAndImageHandlersInParallel() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        ChatService chat = chatService(input -> {
            awaitTogether(barrier, active, peak);
            return "杭州今天晴，25℃。";
        });
        ImageGenService image = imageService(prompt -> {
            awaitTogether(barrier, active, peak);
            return "png".getBytes(StandardCharsets.UTF_8);
        });
        TaskPlanner planner = ignored -> List.of(
            new AgentTask(
                "weather", 0, AgentTaskType.CHAT_TOOL,
                "查询杭州今天的天气", List.of()),
            new AgentTask(
                "image", 1, AgentTaskType.IMAGE_GENERATION,
                "生成一张西湖图片", List.of()));

        try (AgentOrchestrator orchestrator = orchestrator(chat, image, planner, 3, 2)) {
            AgentResponse response = orchestrator.execute(
                "查询杭州天气，同时生成一张西湖图片", "");

            assertTrue(peak.get() >= 2);
            assertTrue(response.text().contains("杭州今天晴"));
            assertTrue(response.text().contains("图片已生成"));
            assertEquals(1, response.attachments().size());
            assertEquals(
                AgentAttachment.AttachmentType.IMAGE,
                response.attachments().get(0).type());
        }
    }

    @Test
    void runsDependentImageInNextOuterRoundWithWeatherResult() throws Exception {
        AtomicReference<String> imagePrompt = new AtomicReference<>("");
        ChatService chat = chatService(input -> "杭州今天有雨，适合雨景氛围。");
        ImageGenService image = imageService(prompt -> {
            imagePrompt.set(prompt);
            return new byte[] {1, 2, 3};
        });
        TaskPlanner planner = ignored -> List.of(
            new AgentTask(
                "weather", 0, AgentTaskType.CHAT_TOOL,
                "查询杭州今天的天气", List.of()),
            new AgentTask(
                "image", 1, AgentTaskType.IMAGE_GENERATION,
                "根据天气生成西湖图片", List.of("weather")));

        try (AgentOrchestrator orchestrator = orchestrator(chat, image, planner, 3, 2)) {
            AgentResponse response = orchestrator.execute(
                "根据杭州今天的天气生成一张西湖图片", "");

            assertTrue(imagePrompt.get().contains("杭州今天有雨"));
            assertEquals(1, response.attachments().size());
        }
    }

    @Test
    void preservesSuccessfulTextWhenImageTaskFails() throws Exception {
        ChatService chat = chatService(input -> "天气查询成功");
        ImageGenService image = imageService(prompt -> {
            throw new IllegalStateException("图片服务异常");
        });
        TaskPlanner planner = ignored -> List.of(
            new AgentTask(
                "weather", 0, AgentTaskType.CHAT_TOOL,
                "查询天气", List.of()),
            new AgentTask(
                "image", 1, AgentTaskType.IMAGE_GENERATION,
                "生成图片", List.of()));

        try (AgentOrchestrator orchestrator = orchestrator(chat, image, planner, 2, 2)) {
            AgentResponse response = orchestrator.execute("查询天气并生成图片", "");

            assertTrue(response.text().contains("天气查询成功"));
            assertTrue(response.text().contains("图片服务异常"));
            assertTrue(response.attachments().isEmpty());
        }
    }

    @Test
    void fallsBackToInnerChatLoopWhenPlanningFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatService chat = chatService(input -> {
            calls.incrementAndGet();
            return "原始流程回答";
        });
        TaskPlanner planner = ignored -> {
            throw new IllegalStateException("规划异常");
        };

        try (AgentOrchestrator orchestrator = orchestrator(
            chat, imageService(prompt -> new byte[] {1}), planner, 2, 2)) {
            AgentResponse response = orchestrator.execute("原始问题", "");

            assertEquals("原始流程回答", response.text());
            assertEquals(1, calls.get());
        }
    }

    private AgentOrchestrator orchestrator(
        ChatService chat,
        ImageGenService image,
        TaskPlanner planner,
        int rounds,
        int parallelism
    ) {
        return new AgentOrchestrator(
            chat,
            planner,
            List.of(
                new ChatAgentTaskHandler(chat),
                new ImageGenerationAgentTaskHandler(image)),
            true,
            rounds,
            parallelism);
    }

    private ChatService chatService(ThrowingFunction<String, String> responder) {
        return new ChatService() {
            @Override
            public String chat(String userText, String history) throws Exception {
                return responder.apply(userText);
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
    }

    private ImageGenService imageService(ThrowingFunction<String, byte[]> generator) {
        return new ImageGenService() {
            @Override
            public byte[] generateImage(String prompt) throws Exception {
                return generator.apply(prompt);
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
    }

    private void awaitTogether(
        CyclicBarrier barrier, AtomicInteger active, AtomicInteger peak
    ) throws Exception {
        int current = active.incrementAndGet();
        peak.accumulateAndGet(current, Math::max);
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } finally {
            active.decrementAndGet();
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
