package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.intent.RuleBasedIntentRecognizer;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliCommandMessageHandlerTests {

    @Test
    void routesSubscriptionByTitleWithoutFallingBackToAgent() {
        BilibiliCommandHandler commandHandler =
            mock(BilibiliCommandHandler.class);
        WeChatOutboundGateway gateway = mock(WeChatOutboundGateway.class);
        when(commandHandler.handleSubscribeByTitle(
            "wechat-user", "紫罗兰的永恒花园"))
            .thenReturn("找到作品");
        BilibiliCommandMessageHandler handler =
            new BilibiliCommandMessageHandler(
                commandHandler,
                gateway,
                new RuleBasedIntentRecognizer());
        WeixinMessage message =
            message("我想订阅紫罗兰的永恒花园");

        assertTrue(handler.canHandle(message));
        handler.handle(mock(ILinkClient.class), message);

        verify(commandHandler).handleSubscribeByTitle(
            "wechat-user", "紫罗兰的永恒花园");
        verify(gateway).sendText("wechat-user", "找到作品");
    }

    @Test
    void routesWatchedTitleWithoutFallingBackToAgent() {
        BilibiliCommandHandler commandHandler =
            mock(BilibiliCommandHandler.class);
        WeChatOutboundGateway gateway = mock(WeChatOutboundGateway.class);
        when(commandHandler.handleMarkStateByTitle(
            "wechat-user", "航海王：红发歌姬", "watched"))
            .thenReturn("已标记");
        BilibiliCommandMessageHandler handler =
            new BilibiliCommandMessageHandler(
                commandHandler,
                gateway,
                new RuleBasedIntentRecognizer());
        WeixinMessage message =
            message("我已经看过航海王：红发歌姬");

        assertTrue(handler.canHandle(message));
        handler.handle(mock(ILinkClient.class), message);

        verify(commandHandler).handleMarkStateByTitle(
            "wechat-user", "航海王：红发歌姬", "watched");
        verify(gateway).sendText("wechat-user", "已标记");
    }

    private WeixinMessage message(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("wechat-user");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }
}
