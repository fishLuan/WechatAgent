package com.clawbot.wechatbot.messaging;

import com.github.wechat.ilink.sdk.ILinkClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeChatClientRegistryTests {

    @Test
    void selectsClientThatReceivedTheUsersMessage() {
        ILinkClient first = loggedInClient();
        ILinkClient second = loggedInClient();
        WeChatClientRegistry registry = new WeChatClientRegistry();
        registry.registerClient(first);
        registry.registerClient(second);
        registry.bindUser("user-2", second);

        assertSame(second, registry.requireClient("user-2"));
    }

    @Test
    void usesOnlyLoggedInClientWhenThereIsNoUserBinding() {
        ILinkClient client = loggedInClient();
        WeChatClientRegistry registry = new WeChatClientRegistry();
        registry.registerClient(client);

        assertSame(client, registry.requireClient("user-1"));
    }

    @Test
    void refusesToGuessBetweenMultipleAccounts() {
        WeChatClientRegistry registry = new WeChatClientRegistry();
        registry.registerClient(loggedInClient());
        registry.registerClient(loggedInClient());

        assertThrows(IllegalStateException.class,
            () -> registry.requireClient("unknown-user"));
    }

    @Test
    void reportsUserReadyOnlyAfterAnInboundMessageBindsTheClient() {
        ILinkClient client = loggedInClient();
        WeChatClientRegistry registry = new WeChatClientRegistry();
        registry.registerClient(client);

        assertFalse(registry.hasActiveUserBinding("user-1"));

        registry.bindUser("user-1", client);

        assertTrue(registry.hasActiveUserBinding("user-1"));
    }

    private ILinkClient loggedInClient() {
        ILinkClient client = mock(ILinkClient.class);
        when(client.isLoggedIn()).thenReturn(true);
        return client;
    }
}
