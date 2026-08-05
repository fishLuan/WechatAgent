package com.clawbot.wechatbot.feature.weread;

import com.clawbot.wechatbot.base.PlanningBypassMessageHandler;
import com.clawbot.wechatbot.intent.ConversationDomainStore;
import com.clawbot.wechatbot.intent.IntentRecognizer;
import com.clawbot.wechatbot.intent.IntentType;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

/** Deterministic entry for explicit book requests, isolated from video search. */
@Component
public final class WereadCommandMessageHandler implements PlanningBypassMessageHandler {
    private final WereadCommandHandler commands;
    private final WereadProperties properties;
    private final IntentRecognizer intents;
    private final ConversationDomainStore domains;

    public WereadCommandMessageHandler(
        WereadCommandHandler commands, WereadProperties properties,
        IntentRecognizer intents, ConversationDomainStore domains
    ) {
        this.commands = commands;
        this.properties = properties;
        this.intents = intents;
        this.domains = domains;
    }

    @Override
    public boolean canHandle(WeixinMessage message) {
        String text = extract(message).trim();
        if (text.isEmpty()) return false;
        if (isBareIndex(text)) {
            return domains.isActive(message.getFrom_user_id(),
                ConversationDomainStore.Domain.WEREAD);
        }
        return intents.recognize(text).type() == IntentType.WEREAD_QUERY;
    }

    @Override
    public boolean canBypassPlanning(WeixinMessage message) {
        return canHandle(message);
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage message) {
        String userId = message.getFrom_user_id();
        String text = extract(message).trim();
        try {
            String reply = isBareIndex(text)
                ? "这是上一轮的书籍会话。请直接发送书名，或说“搜书+书名”。"
                : properties.hasApiKey()
                    ? commands.handle(text)
                    : "微信读书未配置：请先设置 WEREAD_API_KEY 并重启程序。";
            domains.activate(userId, ConversationDomainStore.Domain.WEREAD);
            client.sendText(userId, reply);
            System.out.println("[SEND-WEREAD] " + reply.replace('\n', ' '));
        } catch (Exception error) {
            String reason = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
            try {
                client.sendText(userId, "微信读书请求失败：" + reason);
            } catch (Exception sendError) {
                System.err.println("[WEREAD] 发送失败提示失败：" + sendError.getMessage());
            }
        }
    }

    @Override
    public int priority() { return 45; }

    private String extract(WeixinMessage message) {
        if (message == null || message.getItem_list() == null) return "";
        StringBuilder text = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            if (item.getType() == 1 && item.getText_item() != null) {
                text.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem voice = item.getVoice_item();
                if (voice.getText() != null) text.append(voice.getText());
            }
        }
        return text.toString();
    }

    private boolean isBareIndex(String text) {
        return text != null && text.matches("(?:[1-9]|1\\d|20)");
    }
}
