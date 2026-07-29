package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliLinkMessageHandlerTests {

    @Test
    void handlesBilibiliLinksBeforeTextHandler() {
        BilibiliLinkMessageHandler handler = new BilibiliLinkMessageHandler(
            new StubBilibiliContentSource(),
            new BilibiliMessageFormatter());

        assertTrue(handler.canHandle(message("https://www.bilibili.com/video/BV1xx411c7mD")));
        assertTrue(handler.canHandle(message("看看：https://m.bilibili.com/video/BV1xx411c7mD。")));
        assertTrue(handler.canHandle(message("https://live.bilibili.com/6")));
        assertTrue(handler.canHandle(message("b23.tv/abc123")));
        assertFalse(handler.canHandle(message("今天吃什么")));
        assertTrue(handler.priority() < 100);
    }

    @Test
    void resolvesLinkAndSendsStructuredReply() throws Exception {
        BilibiliContentSource source = mock(BilibiliContentSource.class);
        ILinkClient client = mock(ILinkClient.class);
        BilibiliContent content =
            new BilibiliContent(ContentType.SERIES, "BV1xx411c7mD", "字幕君交流场所");
        content.setPageUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        when(source.resolveUrl("https://www.bilibili.com/video/BV1xx411c7mD"))
            .thenReturn(content);
        BilibiliLinkMessageHandler handler = new BilibiliLinkMessageHandler(
            source,
            new BilibiliMessageFormatter());

        handler.handle(client, message("看看 https://www.bilibili.com/video/BV1xx411c7mD"));

        verify(source).resolveUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        verify(client).sendTextWithTyping(
            eq("wechat-user"),
            contains("字幕君交流场所"),
            anyLong());
    }

    @Test
    void trimsTrailingPunctuationBeforeResolving() throws Exception {
        BilibiliContentSource source = mock(BilibiliContentSource.class);
        ILinkClient client = mock(ILinkClient.class);
        BilibiliContent content =
            new BilibiliContent(ContentType.SERIES, "BV1xx411c7mD", "测试视频");
        when(source.resolveUrl("https://m.bilibili.com/video/BV1xx411c7mD"))
            .thenReturn(content);
        BilibiliLinkMessageHandler handler = new BilibiliLinkMessageHandler(
            source,
            new BilibiliMessageFormatter());

        handler.handle(client, message("看看：https://m.bilibili.com/video/BV1xx411c7mD。"));

        verify(source).resolveUrl("https://m.bilibili.com/video/BV1xx411c7mD");
    }

    private WeixinMessage message(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("wechat-user");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }

    private static class StubBilibiliContentSource implements BilibiliContentSource {
        @Override
        public BilibiliContent resolveUrl(String bilibiliUrl) {
            return new BilibiliContent(ContentType.SERIES, "BV1xx411c7mD", "测试视频");
        }

        @Override
        public Optional<BilibiliContent> findByContentId(
            ContentType contentType,
            String contentId
        ) {
            return Optional.empty();
        }

        @Override
        public List<BilibiliContent> findCandidates(ContentType contentType, int limit) {
            return List.of();
        }

        @Override
        public BilibiliContent refresh(BilibiliContent content) {
            return content;
        }
    }
}
