package com.clawbot.wechatbot.service.multitask;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiTaskChatServiceTests {

    @Test
    void executesIndependentTasksConcurrentlyButKeepsOriginalOrder() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ChatService delegate = stub(text -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(text.contains("任务一") ? 120 : 30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return text.contains("任务一") ? "答案一" : "答案二";
        });

        try (MultiTaskChatService service = new MultiTaskChatService(
            delegate, ignored -> List.of("任务一", "任务二"), true, 2)) {
            String reply = service.chat("同时处理两个问题", "");

            assertTrue(peak.get() >= 2);
            assertTrue(reply.indexOf("答案一") < reply.indexOf("答案二"));
            assertTrue(reply.startsWith("1. 【任务一】"));
        }
    }

    @Test
    void oneFailedTaskDoesNotDiscardOtherAnswers() throws Exception {
        ChatService delegate = stub(text -> {
            if (text.contains("失败任务")) throw new IllegalStateException("模拟失败");
            return "成功答案";
        });

        try (MultiTaskChatService service = new MultiTaskChatService(
            delegate, ignored -> List.of("正常任务", "失败任务"), true, 2)) {
            String reply = service.chat("两个任务", "");

            assertTrue(reply.contains("成功答案"));
            assertTrue(reply.contains("处理失败：模拟失败"));
        }
    }

    @Test
    void fallsBackToOriginalFlowWhenPlanningFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatService delegate = stub(text -> {
            calls.incrementAndGet();
            return "原流程答案";
        });

        try (MultiTaskChatService service = new MultiTaskChatService(
            delegate, ignored -> {
                throw new IllegalStateException("规划服务异常");
            }, true, 2)) {
            assertEquals("原流程答案", service.chat("原始问题", ""));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void keepsSingleRequirementOnOriginalFlow() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatService delegate = stub(text -> {
            calls.incrementAndGet();
            assertEquals("原始问题", text);
            return "单任务答案";
        });

        try (MultiTaskChatService service = new MultiTaskChatService(
            delegate, ignored -> List.of("模型改写的问题"), true, 2)) {
            assertEquals("单任务答案", service.chat("原始问题", ""));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void plannerParserRejectsTooManyTasksWithoutDroppingRequirements() throws Exception {
        DeepSeekClient client = new DeepSeekClient(
            "test-key", "test-model", "https://example.invalid",
            0.8, 100, 1, 1);
        LlmTaskPlanner planner = new LlmTaskPlanner(client, 2);

        List<String> tasks = planner.parseTasks(
            "原始完整问题",
            "```json\n{\"tasks\":[\"一\",\"二\",\"三\"]}\n```");

        assertEquals(List.of("原始完整问题"), tasks);
    }

    private ChatService stub(Function<String, String> responder) {
        return new ChatService() {
            @Override
            public String chat(String userText, String history) {
                return responder.apply(userText);
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
    }
}
