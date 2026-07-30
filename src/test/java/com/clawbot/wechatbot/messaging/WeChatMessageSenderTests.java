package com.clawbot.wechatbot.messaging;

import com.clawbot.wechatbot.notification.NotificationService;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeChatMessageSenderTests {

    @Test
    void doesNotSplitEmojiSurrogatePairAcrossTextMessages() throws Exception {
        WeChatClientRegistry registry = mock(WeChatClientRegistry.class);
        NotificationService notifications = mock(NotificationService.class);
        ILinkClient client = mock(ILinkClient.class);
        when(registry.requireClient("user-1")).thenReturn(client);
        WeChatMessageSender sender =
            new WeChatMessageSender(registry, notifications);
        String text = "a".repeat(1499) + "😀" + "b";

        sender.sendText("user-1", text);

        ArgumentCaptor<String> chunks = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(2))
            .sendText(org.mockito.ArgumentMatchers.eq("user-1"), chunks.capture());
        List<String> sent = chunks.getAllValues();
        assertEquals(text, String.join("", sent));
        assertFalse(Character.isHighSurrogate(
            sent.get(0).charAt(sent.get(0).length() - 1)));
        assertFalse(Character.isLowSurrogate(sent.get(1).charAt(0)));
    }
}
