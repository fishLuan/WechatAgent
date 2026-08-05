package com.clawbot.wechatbot.service.agent;

import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiTaskPlanningGateTests {

    @Test
    void plansTextBeforeDomainRouting() {
        TaskPlanner planner = text -> List.of(
            new AgentTask(
                "task-1", 0, AgentTaskType.CHAT_TOOL,
                "订阅牧神记", List.of()),
            new AgentTask(
                "task-2", 1, AgentTaskType.CHAT_TOOL,
                "设置电影推送时间20:00", List.of()));
        MultiTaskPlanningGate gate = new MultiTaskPlanningGate(planner);

        List<AgentTask> tasks = gate.plan(message(
            "订阅牧神记，然后设置电影推送时间20:00")).orElseThrow();

        assertEquals(2, tasks.size());
        assertEquals("订阅牧神记", tasks.get(0).instruction());
        assertEquals("设置电影推送时间20:00", tasks.get(1).instruction());
    }

    @Test
    void fallsBackToExistingRoutingWhenPlanningFails() {
        TaskPlanner planner = text -> {
            throw new IllegalStateException("模拟规划失败");
        };
        MultiTaskPlanningGate gate = new MultiTaskPlanningGate(planner);

        assertTrue(gate.plan(message(
            "订阅牧神记，然后设置电影推送时间20:00")).isEmpty());
    }

    @Test
    void skipsLlmPlanningForOrdinarySingleTaskText() {
        AtomicInteger calls = new AtomicInteger();
        TaskPlanner planner = text -> {
            calls.incrementAndGet();
            return List.of(new AgentTask(
                "task-1", 0, AgentTaskType.CHAT_TOOL, text, List.of()));
        };
        MultiTaskPlanningGate gate = new MultiTaskPlanningGate(planner);

        assertTrue(gate.plan(message("杭州今天天气怎么样")).isEmpty());
        assertTrue(gate.plan(message("想看治愈冒险动漫")).isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void recognizesCommonMultiTaskStructuresLocally() {
        MultiTaskPlanningGate gate = new MultiTaskPlanningGate(text -> List.of());

        assertTrue(gate.looksLikeMultipleTasks("查杭州天气，然后设置明早提醒"));
        assertTrue(gate.looksLikeMultipleTasks("分别总结文档并生成一份周报"));
        assertTrue(gate.looksLikeMultipleTasks("第一查汇率，第二设置提醒"));
    }

    @Test
    void skipsPlanningWhenPlannerIsNotConfigured() {
        TaskPlanner planner = new TaskPlanner() {
            @Override
            public List<AgentTask> plan(String userText) {
                throw new AssertionError("未配置时不应调用");
            }

            @Override
            public boolean isConfigured() {
                return false;
            }
        };

        assertTrue(new MultiTaskPlanningGate(planner)
            .plan(message("订阅牧神记，然后设置推送时间"))
            .isEmpty());
    }

    @Test
    void keepsExistingAttachmentHandlersWhenAgentIsDisabled() {
        TaskPlanner planner = text -> {
            throw new AssertionError("Agent 关闭时不应规划附件");
        };

        assertTrue(new MultiTaskPlanningGate(planner, false)
            .plan(imageMessage())
            .isEmpty());
    }

    @Test
    void plansImageAttachmentWithExplicitAttachmentMetadata() {
        AtomicReference<String> planningInput = new AtomicReference<>();
        TaskPlanner planner = text -> {
            planningInput.set(text);
            return List.of(new AgentTask(
                "task-1",
                0,
                AgentTaskType.IMAGE_UNDERSTANDING,
                "描述图片",
                List.of()));
        };
        MultiTaskPlanningGate gate = new MultiTaskPlanningGate(planner);
        WeixinMessage message = imageMessage();

        List<AgentTask> tasks = gate.plan(message).orElseThrow();

        assertTrue(gate.hasSupportedAttachment(message));
        assertEquals(AgentTaskType.IMAGE_UNDERSTANDING, tasks.get(0).type());
        assertTrue(planningInput.get().contains("【附件】"));
        assertTrue(planningInput.get().contains("图片 1"));
        assertTrue(planningInput.get().contains("描述并分析我上传的图片"));
    }

    @Test
    void preservesTaskLimitExceededOutcomeForMessageEntry() {
        TaskPlanner planner = new TaskPlanner() {
            @Override
            public List<AgentTask> plan(String userText) {
                return List.of();
            }

            @Override
            public TaskPlan planDetailed(String userText) {
                return TaskPlan.limitExceeded(12, 10);
            }
        };

        TaskPlan plan = new MultiTaskPlanningGate(planner)
            .planDetailed(message("包含很多任务"))
            .orElseThrow();

        assertTrue(plan.limitExceeded());
        assertTrue(plan.userMessage().contains("12 项任务"));
        assertTrue(new MultiTaskPlanningGate(planner)
            .plan(message("包含很多任务"))
            .isEmpty());
    }

    private WeixinMessage message(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("user-1");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }

    private WeixinMessage imageMessage() {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("user-1");
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        message.setItem_list(List.of(item));
        return message;
    }
}
