package com.clawbot.wechatbot;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.base.PlannedMessageHandler;
import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.memory.ConversationMemoryService;
import com.clawbot.wechatbot.messaging.MessageDispatchCoordinator;
import com.clawbot.wechatbot.messaging.WeChatClientRegistry;
import com.clawbot.wechatbot.notification.NotificationService;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.MultiTaskPlanningGate;
import com.clawbot.wechatbot.service.agent.TaskPlan;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeChatBotMessageRoutingTests {

    @Test
    void duplicateMessageIsDroppedBeforeAnyHandler() {
        MessageHandler handler = mock(MessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        ILinkClient client = mock(ILinkClient.class);
        when(memory.markMessageProcessed("user-1", 100L))
            .thenReturn(false);
        WeChatBot bot = bot(handler, registry, memory);

        bot.routeMessages(client, List.of(message(100L)));

        verify(handler, never()).canHandle(
            org.mockito.ArgumentMatchers.any());
        verify(registry, never()).bindUser(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deduplicationFailureDoesNotBlockMessageHandling() {
        MessageHandler handler = mock(MessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        ILinkClient client = mock(ILinkClient.class);
        when(memory.markMessageProcessed("user-1", 100L))
            .thenThrow(new IllegalStateException("Mongo unavailable"));
        WeixinMessage message = message(100L);
        when(handler.canHandle(message)).thenReturn(true);
        WeChatBot bot = bot(handler, registry, memory);

        bot.routeMessages(client, List.of(message));

        verify(registry).bindUser("user-1", client);
        verify(handler).handle(client, message);
    }

    @Test
    void multiTaskPlanBypassesEarlierDomainHandler() {
        MessageHandler bilibiliHandler = mock(MessageHandler.class);
        PlannedMessageHandler plannedHandler =
            mock(PlannedMessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        MultiTaskPlanningGate gate = mock(MultiTaskPlanningGate.class);
        ILinkClient client = mock(ILinkClient.class);
        WeixinMessage message = message(
            101L, "订阅牧神记，然后设置电影推送时间20:00");
        List<AgentTask> tasks = List.of(
            new AgentTask(
                "task-1", 0, AgentTaskType.CHAT_TOOL,
                "订阅牧神记", List.of()),
            new AgentTask(
                "task-2", 1, AgentTaskType.CHAT_TOOL,
                "设置电影推送时间20:00", List.of()));
        when(memory.markMessageProcessed("user-1", 101L)).thenReturn(true);
        when(gate.planDetailed(message))
            .thenReturn(Optional.of(TaskPlan.accepted(tasks, 10)));
        when(bilibiliHandler.priority()).thenReturn(50);
        when(plannedHandler.priority()).thenReturn(100);
        WeChatBot bot = bot(
            List.of(bilibiliHandler, plannedHandler),
            registry,
            memory,
            gate);

        bot.routeMessages(client, List.of(message));

        verify(plannedHandler).handlePlanned(client, message, tasks);
        verify(bilibiliHandler, never()).canHandle(message);
    }

    @Test
    void singleTaskPlanKeepsDomainFastPath() {
        MessageHandler bilibiliHandler = mock(MessageHandler.class);
        PlannedMessageHandler plannedHandler =
            mock(PlannedMessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        MultiTaskPlanningGate gate = mock(MultiTaskPlanningGate.class);
        ILinkClient client = mock(ILinkClient.class);
        WeixinMessage message = message(102L, "订阅牧神记");
        List<AgentTask> tasks = List.of(
            new AgentTask(
                "task-1", 0, AgentTaskType.CHAT_TOOL,
                "订阅牧神记", List.of()));
        when(memory.markMessageProcessed("user-1", 102L)).thenReturn(true);
        when(gate.planDetailed(message))
            .thenReturn(Optional.of(TaskPlan.accepted(tasks, 10)));
        when(bilibiliHandler.priority()).thenReturn(50);
        when(bilibiliHandler.canHandle(message)).thenReturn(true);
        when(plannedHandler.priority()).thenReturn(100);
        WeChatBot bot = bot(
            List.of(bilibiliHandler, plannedHandler),
            registry,
            memory,
            gate);

        bot.routeMessages(client, List.of(message));

        verify(bilibiliHandler).handle(client, message);
        verify(plannedHandler, never()).handlePlanned(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void singleImageTaskUsesUnifiedPlannedRoute() {
        MessageHandler imageHandler = mock(MessageHandler.class);
        PlannedMessageHandler plannedHandler =
            mock(PlannedMessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        MultiTaskPlanningGate gate = mock(MultiTaskPlanningGate.class);
        ILinkClient client = mock(ILinkClient.class);
        WeixinMessage message = imageMessage(103L);
        List<AgentTask> tasks = List.of(new AgentTask(
            "task-1",
            0,
            AgentTaskType.IMAGE_UNDERSTANDING,
            "描述图片",
            List.of()));
        when(memory.markMessageProcessed("user-1", 103L)).thenReturn(true);
        when(gate.planDetailed(message))
            .thenReturn(Optional.of(TaskPlan.accepted(tasks, 10)));
        when(gate.hasSupportedAttachment(message)).thenReturn(true);
        when(imageHandler.priority()).thenReturn(10);
        when(plannedHandler.priority()).thenReturn(100);
        WeChatBot bot = bot(
            List.of(imageHandler, plannedHandler),
            registry,
            memory,
            gate);

        bot.routeMessages(client, List.of(message));

        verify(plannedHandler).handlePlanned(client, message, tasks);
        verify(imageHandler, never()).canHandle(message);
    }

    @Test
    void rejectedMessageGetsBusyReplyWithoutBeingMarkedProcessed()
        throws Exception {
        MessageHandler handler = mock(MessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        MultiTaskPlanningGate gate = mock(MultiTaskPlanningGate.class);
        MessageDispatchCoordinator dispatcher =
            mock(MessageDispatchCoordinator.class);
        ILinkClient client = mock(ILinkClient.class);
        WeixinMessage message = message(104L, "测试");
        when(dispatcher.dispatch(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(false);
        WeChatBot bot = new WeChatBot(
            mock(BotConfig.class),
            List.of(handler),
            mock(NotificationService.class),
            registry,
            memory,
            gate,
            dispatcher,
            mock(Environment.class));

        bot.routeMessages(client, List.of(message));

        verify(client).sendText(
            "user-1", "当前消息较多，请稍后重新发送这条请求。");
        verify(memory, never()).markMessageProcessed(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
        verify(handler, never()).canHandle(message);
    }

    @Test
    void taskLimitExceededSendsClearReplyWithoutCallingHandlers()
        throws Exception {
        MessageHandler handler = mock(MessageHandler.class);
        ConversationMemoryService memory =
            mock(ConversationMemoryService.class);
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        MultiTaskPlanningGate gate = mock(MultiTaskPlanningGate.class);
        ILinkClient client = mock(ILinkClient.class);
        WeixinMessage message = message(105L, "很多任务");
        when(memory.markMessageProcessed("user-1", 105L)).thenReturn(true);
        when(gate.planDetailed(message)).thenReturn(
            Optional.of(TaskPlan.limitExceeded(12, 10)));
        WeChatBot bot = bot(List.of(handler), registry, memory, gate);

        bot.routeMessages(client, List.of(message));

        verify(client).sendText(
            "user-1",
            "检测到你的消息包含 12 项任务，当前一次最多处理 10 项。"
                + "请拆成多条消息发送，或指定优先处理哪些任务。");
        verify(handler, never()).canHandle(message);
    }

    private WeChatBot bot(
        MessageHandler handler,
        WeChatClientRegistry registry,
        ConversationMemoryService memory
    ) {
        return bot(
            List.of(handler),
            registry,
            memory,
            mock(MultiTaskPlanningGate.class));
    }

    private WeChatBot bot(
        List<MessageHandler> handlers,
        WeChatClientRegistry registry,
        ConversationMemoryService memory,
        MultiTaskPlanningGate gate
    ) {
        return new WeChatBot(
            mock(BotConfig.class),
            handlers,
            mock(NotificationService.class),
            registry,
            memory,
            gate,
            immediateDispatcher(),
            mock(Environment.class));
    }

    private MessageDispatchCoordinator immediateDispatcher() {
        return new MessageDispatchCoordinator() {
            @Override
            public boolean dispatch(
                String userId,
                Runnable task,
                Consumer<Throwable> failureHandler
            ) {
                try {
                    task.run();
                } catch (Throwable error) {
                    failureHandler.accept(error);
                }
                return true;
            }

            @Override
            public void close() {
            }
        };
    }

    private WeixinMessage message(long id) {
        WeixinMessage message = new WeixinMessage();
        message.setMessage_id(id);
        message.setFrom_user_id("user-1");
        return message;
    }

    private WeixinMessage message(long id, String text) {
        WeixinMessage message = message(id);
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }

    private WeixinMessage imageMessage(long id) {
        WeixinMessage message = message(id);
        MessageItem image = new MessageItem();
        image.setImage_item(new ImageItem());
        message.setItem_list(List.of(image));
        return message;
    }
}
