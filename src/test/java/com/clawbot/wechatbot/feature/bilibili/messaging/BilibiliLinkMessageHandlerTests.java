package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliLinkMessageHandlerTests {

    @Test
    void handlesBilibiliLinksBeforeTextHandler() {
        BilibiliLinkMessageHandler handler = new BilibiliLinkMessageHandler(
            mock(BilibiliSubscriptionService.class),
            new BilibiliMessageFormatter(),
            mock(WeChatOutboundGateway.class));

        assertTrue(handler.canHandle(message(
            "https://www.bilibili.com/video/BV1xx411c7mD")));
        assertTrue(handler.canHandle(message(
            "看看：https://m.bilibili.com/video/BV1xx411c7mD。")));
        assertTrue(handler.canHandle(message(
            "https://live.bilibili.com/6")));
        assertTrue(handler.canHandle(message("b23.tv/abc123")));
        assertFalse(handler.canHandle(message("今天吃什么")));
        assertTrue(handler.priority() < 100);
    }

    @Test
    void subscribesFromLinkAndSendsResult() {
        BilibiliSubscriptionService subscriptionService =
            mock(BilibiliSubscriptionService.class);
        WeChatOutboundGateway gateway = mock(WeChatOutboundGateway.class);
        when(subscriptionService.subscribeByUrl(
            "wechat-user",
            "https://www.bilibili.com/bangumi/play/ss39444"))
            .thenReturn(success());
        BilibiliLinkMessageHandler handler =
            new BilibiliLinkMessageHandler(
                subscriptionService,
                new BilibiliMessageFormatter(),
                gateway);

        handler.handle(
            mock(ILinkClient.class),
            message("https://www.bilibili.com/bangumi/play/ss39444"));

        verify(subscriptionService).subscribeByUrl(
            "wechat-user",
            "https://www.bilibili.com/bangumi/play/ss39444");
        verify(gateway).sendText(
            org.mockito.ArgumentMatchers.eq("wechat-user"),
            org.mockito.ArgumentMatchers.contains("订阅成功"));
    }

    @Test
    void trimsTrailingPunctuationBeforeResolving() {
        BilibiliSubscriptionService subscriptionService =
            mock(BilibiliSubscriptionService.class);
        WeChatOutboundGateway gateway = mock(WeChatOutboundGateway.class);
        when(subscriptionService.subscribeByUrl(
            "wechat-user",
            "https://m.bilibili.com/video/BV1xx411c7mD"))
            .thenReturn(success());
        BilibiliLinkMessageHandler handler =
            new BilibiliLinkMessageHandler(
                subscriptionService,
                new BilibiliMessageFormatter(),
                gateway);

        handler.handle(
            mock(ILinkClient.class),
            message("看看：https://m.bilibili.com/video/BV1xx411c7mD。"));

        verify(subscriptionService).subscribeByUrl(
            "wechat-user",
            "https://m.bilibili.com/video/BV1xx411c7mD");
    }

    private WeixinMessage message(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("wechat-user");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }

    private SubscriptionResult success() {
        return new SubscriptionResult(
            true,
            false,
            "subscription-1",
            "测试作品",
            "39444",
            SubscriptionStatus.ACTIVE,
            7,
            "订阅成功");
    }
}
