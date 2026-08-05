package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.VisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.clawbot.wechatbot.service.agent.acceptance.DefaultTaskAcceptanceEvaluator;
import com.clawbot.wechatbot.service.agent.replan.AgentReplanPolicy;
import com.clawbot.wechatbot.service.agent.replan.PlanMutation;
import com.clawbot.wechatbot.service.agent.replan.PlanMutationApplier;
import com.clawbot.wechatbot.service.agent.replan.PlanMutationType;
import com.clawbot.wechatbot.service.agent.replan.PlanMutationValidator;
import com.clawbot.wechatbot.service.agent.replan.ReplanRequest;
import com.clawbot.wechatbot.service.agent.replan.ReplanResult;
import com.clawbot.wechatbot.service.agent.replan.TaskReplanner;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.service.agent.reference.ReferencePolicy;
import com.clawbot.wechatbot.service.agent.reference.ResultReferenceResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void blocksDependentTaskWhenAcceptanceCriteriaRequestReplan() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger imageCalls = new AtomicInteger();
        ChatService chat = chatService(input -> "{\"city\":\"上海\",\"weather\":\"晴\"}");
        ImageGenService image = imageService(prompt -> {
            imageCalls.incrementAndGet();
            return new byte[] {1};
        });
        AgentTask weather = new AgentTask(
            "weather", 0, AgentTaskType.CHAT_TOOL, "", "查询杭州天气",
            mapper.createObjectNode().put("city", "杭州"),
            mapper.createObjectNode().put("city", "string").put("weather", "string"),
            List.of(new AcceptanceCriterion(
                "城市必须为杭州", "$.city", AcceptanceOperator.EQUALS,
                mapper.valueToTree("杭州"), true)),
            List.of());
        AgentTask imageTask = new AgentTask(
            "image", 1, AgentTaskType.IMAGE_GENERATION,
            "根据天气生成图片", List.of("weather"));
        TaskPlanner planner = ignored -> List.of(weather, imageTask);

        try (AgentOrchestrator orchestrator = orchestrator(chat, image, planner, 3, 2)) {
            AgentResponse response = orchestrator.execute("查询杭州天气并生成图片", "");

            assertEquals(0, imageCalls.get());
            assertTrue(response.text().contains("REPLAN/TASK_ACCEPTANCE_FAILED"));
            assertTrue(response.text().contains("任务依赖未通过验收"));
        }
    }

    @Test
    void automaticallyRetriesThenUnlocksDependentTask() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger firstAttempts = new AtomicInteger();
        AtomicInteger dependentCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();
        ChatService chat = chatService(input -> {
            if (input.contains("任务拆解过程：\n查询数据")) {
                if (firstAttempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("临时故障");
                }
                return "{\"ok\":true}";
            }
            dependentCalls.incrementAndGet();
            return "后续任务完成";
        });
        AgentTask first = new AgentTask(
            "first", 0, AgentTaskType.CHAT_TOOL, "", "查询数据",
            mapper.createObjectNode(), mapper.createObjectNode().put("ok", "boolean"),
            List.of(), List.of());
        AgentTask second = new AgentTask(
            "second", 1, AgentTaskType.CHAT_TOOL, "处理查询结果", List.of("first"));
        TaskReplanner replanner = replanner(request -> {
            replanCalls.incrementAndGet();
            return new ReplanResult(List.of(), "不应调用");
        });

        try (AgentOrchestrator orchestrator = dynamicOrchestrator(
            chat, ignored -> List.of(first, second), replanner,
            new AgentReplanPolicy(true, 2, 2, 10, 10, Duration.ofSeconds(2)))) {
            AgentResponse response = orchestrator.execute("查询后处理", "");

            assertEquals(2, firstAttempts.get());
            assertEquals(1, dependentCalls.get());
            assertEquals(0, replanCalls.get());
            assertTrue(response.text().contains("后续任务完成"));
        }
    }

    @Test
    void dynamicallyReplacesFailedTaskAndContinuesPlan() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicInteger repairedCalls = new AtomicInteger();
        AtomicInteger dependentCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();
        ChatService chat = chatService(input -> {
            if (input.contains("任务拆解过程：\n原搜索")) {
                originalCalls.incrementAndGet();
                return "{\"items\":[]}";
            }
            if (input.contains("任务拆解过程：\n改进搜索")) {
                repairedCalls.incrementAndGet();
                return "{\"items\":[{\"title\":\"火影忍者\"}]}";
            }
            dependentCalls.incrementAndGet();
            return "已选择并完成后续操作";
        });
        AcceptanceCriterion notEmpty = new AcceptanceCriterion(
            "搜索结果不能为空", "$.items", AcceptanceOperator.NOT_EMPTY,
            null, true);
        AgentTask search = new AgentTask(
            "search", 0, AgentTaskType.CHAT_TOOL, "", "原搜索",
            mapper.createObjectNode(), mapper.createObjectNode().put("items", "array"),
            List.of(notEmpty), List.of());
        AgentTask next = new AgentTask(
            "next", 1, AgentTaskType.CHAT_TOOL, "执行后续操作", List.of("search"));
        TaskReplanner replanner = replanner(request -> {
            replanCalls.incrementAndGet();
            AgentTask replacement = new AgentTask(
                "search", 0, AgentTaskType.CHAT_TOOL, "", "改进搜索",
                mapper.createObjectNode(), mapper.createObjectNode().put("items", "array"),
                List.of(notEmpty), List.of());
            return new ReplanResult(
                List.of(new PlanMutation(
                    PlanMutationType.REPLACE_TASK, "search", replacement,
                    "更换搜索方式")),
                "修复空结果");
        });

        try (AgentOrchestrator orchestrator = dynamicOrchestrator(
            chat, ignored -> List.of(search, next), replanner,
            new AgentReplanPolicy(true, 2, 1, 10, 10, Duration.ofSeconds(2)))) {
            AgentResponse response = orchestrator.execute("搜索后执行", "");

            assertEquals(1, originalCalls.get());
            assertEquals(1, repairedCalls.get());
            assertEquals(1, dependentCalls.get());
            assertEquals(1, replanCalls.get());
            assertTrue(response.text().contains("已选择并完成后续操作"));
        }
    }

    @Test
    void resolvesVerifiedReferenceBeforeExecutingDependentTask() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> dependentPrompt = new AtomicReference<>("");
        ChatService chat = chatService(input -> {
            if (input.contains("任务拆解过程：\n搜索作品")) {
                return "{\"selectedItem\":{\"title\":\"火影忍者\","
                    + "\"seasonId\":\"ss123\"}}";
            }
            dependentPrompt.set(input);
            return "订阅完成";
        });
        AgentTask search = new AgentTask(
            "search", 0, AgentTaskType.CHAT_TOOL, "搜索作品", List.of());
        ObjectNode subscribeInput = mapper.createObjectNode();
        subscribeInput.putObject("seasonId")
            .put("$ref", "search.output.selectedItem.seasonId");
        AgentTask subscribe = new AgentTask(
            "subscribe", 1, AgentTaskType.CHAT_TOOL, "", "订阅作品",
            subscribeInput, mapper.createObjectNode(), List.of(), List.of("search"));

        try (AgentOrchestrator orchestrator = orchestrator(
            chat, imageService(prompt -> new byte[] {1}),
            ignored -> List.of(search, subscribe), 4, 2)) {
            AgentResponse response = orchestrator.execute("搜索并订阅作品", "");

            assertTrue(response.text().contains("订阅完成"));
            assertTrue(dependentPrompt.get().contains("已验证的结构化输入"));
            assertTrue(dependentPrompt.get().contains("\"seasonId\":\"ss123\""));
            assertFalse(dependentPrompt.get().contains("$ref"));
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

    @Test
    void executesEightIndependentTasksInBatchesOfFive() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        ChatService chat = chatService(input -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(80);
                executed.incrementAndGet();
                return "完成：" + input;
            } finally {
                active.decrementAndGet();
            }
        });
        List<AgentTask> tasks = IntStream.rangeClosed(1, 8)
            .mapToObj(index -> new AgentTask(
                "task-" + index,
                index - 1,
                AgentTaskType.CHAT_TOOL,
                "任务" + index,
                List.of()))
            .toList();

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            chat,
            ignored -> tasks,
            List.of(new ChatAgentTaskHandler(chat)),
            true,
            10,
            5,
            8,
            Duration.ofSeconds(5),
            new AgentRequestContextHolder())) {
            AgentResponse response = orchestrator.execute("执行八项任务", "");

            assertEquals(8, executed.get());
            assertEquals(5, peak.get());
            assertTrue(response.text().contains("任务8"));
        }
    }

    @Test
    void returnsClearMessageWhenPlannedTaskTotalExceedsLimit()
        throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        ChatService chat = chatService(input -> {
            chatCalls.incrementAndGet();
            return "不应执行";
        });
        TaskPlanner planner = new TaskPlanner() {
            @Override
            public List<AgentTask> plan(String userText) {
                return List.of();
            }

            @Override
            public TaskPlan planDetailed(String userText) {
                return TaskPlan.limitExceeded(11, 10);
            }
        };

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            chat,
            planner,
            List.of(new ChatAgentTaskHandler(chat)),
            true,
            10,
            5,
            3,
            Duration.ofSeconds(5),
            new AgentRequestContextHolder())) {
            AgentResponse response = orchestrator.execute("十一项任务", "");

            assertTrue(response.text().contains("11 项任务"));
            assertTrue(response.text().contains("最多处理 10 项"));
            assertEquals(0, chatCalls.get());
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

    private AgentOrchestrator dynamicOrchestrator(
        ChatService chat,
        TaskPlanner planner,
        TaskReplanner replanner,
        AgentReplanPolicy policy
    ) {
        ObjectMapper mapper = new ObjectMapper();
        PlanMutationValidator validator = new PlanMutationValidator(
            emptySkills(), 5, 5, policy.maxTotalTasks());
        return new AgentOrchestrator(
            chat,
            planner,
            List.of(new ChatAgentTaskHandler(chat)),
            true,
            10,
            5,
            2,
            Duration.ofSeconds(5),
            new AgentRequestContextHolder(),
            new DefaultTaskAcceptanceEvaluator(mapper),
            replanner,
            new PlanMutationApplier(validator),
            policy,
            new ResultReferenceResolver(mapper, ReferencePolicy.defaults()));
    }

    private TaskReplanner replanner(
        ThrowingFunction<ReplanRequest, ReplanResult> action
    ) {
        return new TaskReplanner() {
            @Override
            public ReplanResult replan(ReplanRequest request) throws Exception {
                return action.apply(request);
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
    }

    private SkillCatalog emptySkills() {
        return new SkillCatalog() {
            @Override public List<SkillDefinition> definitions() { return List.of(); }
            @Override public boolean contains(String name) { return false; }
        };
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
