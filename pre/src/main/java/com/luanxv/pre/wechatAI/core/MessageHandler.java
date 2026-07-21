package com.luanxv.pre.wechatAI.core;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.luanxv.pre.wechatAI.handler.FileMessageHandler;
import com.luanxv.pre.wechatAI.handler.ImageMessageHandler;
import com.luanxv.pre.wechatAI.handler.MessageTypeHandler;
import com.luanxv.pre.wechatAI.handler.TextMessageHandler;
import com.luanxv.pre.wechatAI.handler.VoiceMessageHandler;
import com.luanxv.pre.wechatAI.model.MessageContext;
import com.luanxv.pre.wechatAI.service.AgentIntentService;
import com.luanxv.pre.wechatAI.service.QwenService;
import com.luanxv.pre.wechatAI.service.VoicePreferenceService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;

/** Builds a message context, selects one handler, then sends its reply. */
@Component
public class MessageHandler {
    private final ILinkClient client;
    private final List<MessageTypeHandler> handlers = new ArrayList<>();

    public MessageHandler(ILinkClient client, QwenService qwenService, GeneratedImageStore imageStore,
                          ConversationMemoryService memoryService, VoicePreferenceService voicePreferenceService,
                          AgentIntentService agentIntentService) {
        this.client = client;
        handlers.add(new VoiceMessageHandler(client, qwenService, voicePreferenceService));
        handlers.add(new ImageMessageHandler(client, qwenService, memoryService, imageStore));
        handlers.add(new FileMessageHandler(client, qwenService, memoryService));
        handlers.add(new TextMessageHandler(client, qwenService, voicePreferenceService, agentIntentService,
                imageStore, memoryService));
    }

    public void handleAndReply(WeixinMessage message) {
        try {
            MessageContext context = buildContext(message);
            for (MessageTypeHandler handler : handlers) {
                if (handler.supports(context)) {
                    String reply = handler.handle(context);
                    if (reply != null && !reply.isBlank()) {
                        sendReply(context.getFromUserId(), reply);
                    }
                    return;
                }
            }
        } catch (Exception exception) {
            System.err.println("处理微信消息失败: " + exception.getMessage());
        }
    }

    private MessageContext buildContext(WeixinMessage message) {
        List<MessageItem> items = message.getItem_list();
        MessageItem image = findItem(items, item -> item.getImage_item() != null);
        MessageItem voice = findItem(items, item -> item.getVoice_item() != null);
        MessageItem file = findItem(items, item -> item.getFile_item() != null);
        return MessageContext.builder()
                .fromUserId(message.getFrom_user_id())
                .textContent(extractText(items))
                .imageItem(image)
                .voiceItem(voice)
                .fileItem(file)
                .hasImage(image != null)
                .hasVoice(voice != null)
                .hasFile(file != null)
                .build();
    }

    private String extractText(List<MessageItem> items) {
        if (items == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                text.append(item.getText_item().getText());
            }
        }
        return text.toString();
    }

    private MessageItem findItem(List<MessageItem> items, Predicate<MessageItem> predicate) {
        return items == null ? null : items.stream().filter(predicate).findFirst().orElse(null);
    }

    private void sendReply(String userId, String reply) {
        try {
            if (reply.startsWith("IMAGE:")) {
                client.sendImage(userId, Base64.getDecoder().decode(reply.substring(6)), "generated.png", "AI generated image");
            } else if (reply.startsWith("MP3:")) {
                client.sendFile(userId, Base64.getDecoder().decode(reply.substring(4)), "AI-reply.mp3", "AI voice reply");
            } else {
                System.out.printf("[text] sending reply to %s%n", userId);
                client.sendText(userId, reply);
            }
        } catch (Exception exception) {
            System.err.println("发送微信回复失败: " + exception.getMessage());
        }
    }
}
