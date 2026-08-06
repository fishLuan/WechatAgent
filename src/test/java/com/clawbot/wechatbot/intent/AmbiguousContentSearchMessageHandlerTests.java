package com.clawbot.wechatbot.intent;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import com.clawbot.wechatbot.feature.weread.WereadCommandHandler;
import com.clawbot.wechatbot.feature.weread.WereadProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmbiguousContentSearchMessageHandlerTests {
    @Test
    void asksOnceThenContinuesOriginalTitleWithSelectedVideoDomain() throws Exception {
        WebSearchContentDomainResolver resolver = mock(WebSearchContentDomainResolver.class);
        BilibiliCommandHandler bilibili = mock(BilibiliCommandHandler.class);
        ILinkClient client = mock(ILinkClient.class);
        when(resolver.resolve("三体")).thenReturn(new ContentDomainResolution(
            ContentDomainResolution.Domain.BOTH, 0.9, "book and video"));
        when(bilibili.handleSearchByTitle("user-1", "三体"))
            .thenReturn("影视搜索结果");
        AmbiguousContentSearchMessageHandler handler = new AmbiguousContentSearchMessageHandler(
            new RuleBasedIntentRecognizer(), resolver,
            new PendingContentDomainChoiceStore(), new ConversationDomainStore(),
            mock(WereadCommandHandler.class), mock(WereadProperties.class), bilibili);

        WeixinMessage search = message("搜三体");
        assertTrue(handler.canHandle(search));
        handler.handle(client, search);
        verify(client).sendText(eq("user-1"), contains("1. 书籍"));

        WeixinMessage selection = message("2");
        assertTrue(handler.canHandle(selection));
        handler.handle(client, selection);
        verify(bilibili).handleSearchByTitle("user-1", "三体");
        verify(client).sendText("user-1", "影视搜索结果");
    }

    private WeixinMessage message(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("user-1");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }
}
