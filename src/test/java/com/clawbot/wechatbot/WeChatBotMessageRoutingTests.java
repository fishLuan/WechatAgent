package com.clawbot.wechatbot;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.memory.ConversationMemoryService;
import com.clawbot.wechatbot.messaging.WeChatClientRegistry;
import com.clawbot.wechatbot.notification.NotificationService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private WeChatBot bot(
        MessageHandler handler,
        WeChatClientRegistry registry,
        ConversationMemoryService memory
    ) {
        return new WeChatBot(
            mock(BotConfig.class),
            List.of(handler),
            mock(NotificationService.class),
            registry,
            memory);
    }

    private WeixinMessage message(long id) {
        WeixinMessage message = new WeixinMessage();
        message.setMessage_id(id);
        message.setFrom_user_id("user-1");
        return message;
    }
}
