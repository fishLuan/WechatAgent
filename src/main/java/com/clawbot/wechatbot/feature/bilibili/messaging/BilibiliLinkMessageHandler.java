package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.subscription.BilibiliSubscriptionService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** B站链接专用入口，优先于普通文本和大模型工具循环。 */
public final class BilibiliLinkMessageHandler implements MessageHandler {
    private static final Pattern LINK = Pattern.compile(
        "(?i)((?:https?://)?(?:(?:[a-z0-9-]+\\.)?bilibili\\.com|b23\\.tv)/[^\\s，。！？]+)");

    private final BilibiliSubscriptionService subscriptions;
    private final WeChatOutboundGateway gateway;

    public BilibiliLinkMessageHandler(
        BilibiliSubscriptionService subscriptions,
        BilibiliMessageFormatter ignoredFormatter,
        WeChatOutboundGateway gateway
    ) {
        this.subscriptions = subscriptions;
        this.gateway = gateway;
    }

    @Override
    public boolean canHandle(WeixinMessage message) {
        return findLink(extractText(message)) != null;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage message) {
        String userId = message == null ? null : message.getFrom_user_id();
        String link = findLink(extractText(message));
        if (userId == null || userId.isBlank() || link == null) return;
        String reply;
        try {
            SubscriptionResult result = subscriptions.subscribeByUrl(userId, link);
            reply = BilibiliMessageFormatter.formatSubscription(result);
        } catch (Exception error) {
            reply = BilibiliMessageFormatter.formatResolveFailure(error.getMessage());
        }
        gateway.sendText(userId, reply);
    }

    @Override
    public int priority() {
        return 40;
    }

    private String findLink(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find()) return null;
        String link = matcher.group(1);
        if (!link.startsWith("http")) link = "https://" + link;
        return trimPunctuation(link);
    }

    private String trimPunctuation(String link) {
        int end = link.length();
        while (end > 0 && "。！？，,.;；）)]】》」".indexOf(link.charAt(end - 1)) >= 0) {
            end--;
        }
        return end == 0 ? null : link.substring(0, end);
    }

    static String extractText(WeixinMessage message) {
        if (message == null || message.getItem_list() == null) return "";
        StringBuilder result = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item == null) continue;
            if (item.getType() == 1 && item.getText_item() != null) {
                result.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem voice = item.getVoice_item();
                if (voice.getText() != null) result.append(voice.getText());
            }
        }
        return result.toString();
    }
}
