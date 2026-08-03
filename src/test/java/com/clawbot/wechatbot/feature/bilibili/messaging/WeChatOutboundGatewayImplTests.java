package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeChatOutboundGatewayImplTests {
    @Test
    void requiresPerUserContextInsteadOfOnlyGlobalLogin() {
        MessageSender sender = mock(MessageSender.class);
        when(sender.isReady()).thenReturn(true);
        when(sender.isReadyFor("active-user")).thenReturn(true);
        WeChatOutboundGateway gateway = new WeChatOutboundGatewayImpl(sender);

        assertTrue(gateway.isAvailable("active-user"));
        assertFalse(gateway.isAvailable("stale-user"));
    }
}
