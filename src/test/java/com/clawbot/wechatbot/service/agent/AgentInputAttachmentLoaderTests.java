package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.DocumentService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInputAttachmentLoaderTests {

    @Test
    void downloadsSupportedImageOnce() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        ILinkClient client = mock(ILinkClient.class);
        MessageItem item = imageItem(3L);
        WeixinMessage message = message(item);
        when(client.downloadImageFromMessageItem(item))
            .thenReturn(new byte[] {1, 2, 3});
        AgentInputAttachmentLoader loader =
            new AgentInputAttachmentLoader(documents, 2, 10, 20);

        List<AgentInputAttachment> attachments = loader.load(client, message);

        assertEquals(1, attachments.size());
        assertEquals(
            AgentInputAttachment.AttachmentType.IMAGE,
            attachments.get(0).type());
        verify(client).downloadImageFromMessageItem(item);
    }

    @Test
    void rejectsDeclaredOversizeBeforeDownloading() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        ILinkClient client = mock(ILinkClient.class);
        MessageItem item = imageItem(11L);
        AgentInputAttachmentLoader loader =
            new AgentInputAttachmentLoader(documents, 2, 10, 20);

        assertThrows(
            IllegalArgumentException.class,
            () -> loader.load(client, message(item)));
        verify(client, never()).downloadImageFromMessageItem(item);
    }

    private MessageItem imageItem(long declaredBytes) {
        ImageItem image = new ImageItem();
        image.setHd_size(declaredBytes);
        MessageItem item = new MessageItem();
        item.setImage_item(image);
        return item;
    }

    private WeixinMessage message(MessageItem item) {
        WeixinMessage message = new WeixinMessage();
        message.setItem_list(List.of(item));
        return message;
    }
}
