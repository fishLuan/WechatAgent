package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.VisionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    @Test
    void executesPreplannedTasksWithoutCallingPlannerAgain() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        ChatService chat = chatService(input -> "执行成功");
        TaskPlanner planner = ignored -> {
            plannerCalls.incrementAndGet();
            return List.of(AgentTask.chat("不应重新规划"));
        };
        List<AgentTask> tasks = List.of(
            new AgentTask(
                "first", 0, AgentTaskType.CHAT_TOOL,
                "订阅牧神记", List.of()),
            new AgentTask(
                "second", 1, AgentTaskType.CHAT_TOOL,
                "设置电影推送时间20:00", List.of()));

        try (AgentOrchestrator orchestrator = orchestrator(
            chat, imageService(prompt -> new byte[] {1}), planner, 2, 2)) {
            AgentResponse response =
                orchestrator.executePlanned("原始复合问题", "", tasks);

            assertEquals(0, plannerCalls.get());
            assertTrue(response.text().contains("执行成功"));
        }
    }

    @Test
    void isolatesRequestContextWhenExecutorThreadIsReused() throws Exception {
        AgentRequestContextHolder requestContextHolder =
            new AgentRequestContextHolder();
        ChatService chat = chatService(
            input -> requestContextHolder.currentUserId());
        TaskPlanner planner = ignored -> List.of(AgentTask.chat("回答用户"));

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            chat,
            planner,
            List.of(new ChatAgentTaskHandler(chat)),
            true,
            2,
            1,
            Duration.ofSeconds(5),
            requestContextHolder)) {
            AgentResponse first = orchestrator.execute(
                "第一条消息",
                "",
                new AgentRequestContext("user-a", 1L));
            AgentResponse second = orchestrator.execute(
                "第二条消息",
                "",
                new AgentRequestContext("user-b", 2L));

            assertEquals("user-a", first.text());
            assertEquals("user-b", second.text());
            assertEquals("", requestContextHolder.currentUserId());
        }
    }

    @Test
    void passesImageUnderstandingResultToDependentGenerationTask()
        throws Exception {
        VisionService vision = new VisionService() {
            @Override
            public String understandImage(byte[] imageBytes, String question) {
                return "图片主体是一只橘猫";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        AtomicReference<String> generatedPrompt = new AtomicReference<>();
        ImageGenService image = imageService(prompt -> {
            generatedPrompt.set(prompt);
            return new byte[] {1, 2, 3};
        });
        ChatService chat = chatService(input -> "不应调用");
        List<AgentTask> tasks = List.of(
            new AgentTask(
                "understand", 0, AgentTaskType.IMAGE_UNDERSTANDING,
                "分析上传图片", List.of()),
            new AgentTask(
                "generate", 1, AgentTaskType.IMAGE_GENERATION,
                "生成动漫风格图片", List.of("understand")));
        AgentInputAttachment attachment = new AgentInputAttachment(
            AgentInputAttachment.AttachmentType.IMAGE,
            new byte[] {9, 8, 7},
            "source.jpg");

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            chat,
            ignored -> tasks,
            List.of(
                new ImageUnderstandingAgentTaskHandler(vision),
                new ImageGenerationAgentTaskHandler(image)),
            true,
            3,
            2)) {
            AgentResponse response = orchestrator.executePlanned(
                "[图片]请分析并生成动漫风格图片",
                "",
                tasks,
                new AgentRequestContext("user-1", 3L),
                List.of(attachment));

            assertTrue(generatedPrompt.get().contains("图片主体是一只橘猫"));
            assertEquals(1, response.attachments().size());
        }
    }

    @Test
    void terminatesOuterLoopWhenOverallDeadlineExpires() throws Exception {
        ChatService chat = chatService(input -> "不应调用");
        ImageGenService image = imageService(prompt -> {
            Thread.sleep(1000);
            return new byte[] {1};
        });
        TaskPlanner planner = ignored -> List.of(
            new AgentTask(
                "image", 0, AgentTaskType.IMAGE_GENERATION,
                "生成图片", List.of()));
        long started = System.nanoTime();

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            chat,
            planner,
            List.of(new ImageGenerationAgentTaskHandler(image)),
            true,
            2,
            1,
            Duration.ofMillis(80))) {
            AgentResponse response = orchestrator.execute("生成图片", "");
            long elapsedMillis = Duration.ofNanos(
                System.nanoTime() - started).toMillis();

            assertTrue(response.text().contains("Agent 执行时间超过"));
            assertTrue(elapsedMillis < 800);
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
