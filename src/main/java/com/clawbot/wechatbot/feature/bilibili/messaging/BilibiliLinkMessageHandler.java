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

/** 优先拦截 B 站链接，避免进入大模型网页搜索循环。 */
public class BilibiliLinkMessageHandler implements MessageHandler {
    private static final Pattern BILIBILI_LINK = Pattern.compile(
        "(?i)((?:https?://)?(?:(?:[a-z0-9-]+\\.)?bilibili\\.com|b23\\.tv)/\\S+)");

    private final BilibiliSubscriptionService subscriptionService;
    private final BilibiliMessageFormatter formatter;
    private final WeChatOutboundGateway outboundGateway;

    public BilibiliLinkMessageHandler(
        BilibiliSubscriptionService subscriptionService,
        BilibiliMessageFormatter formatter,
        WeChatOutboundGateway outboundGateway
    ) {
        this.subscriptionService = subscriptionService;
        this.formatter = formatter;
        this.outboundGateway = outboundGateway;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        String text = extractText(msg);
        return firstBilibiliLink(text) != null;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        String link = firstBilibiliLink(extractText(msg));
        if (from == null || from.isBlank() || link == null) return;

        String reply;
        try {
            SubscriptionResult result =
                subscriptionService.subscribeByUrl(from, link);
            reply = BilibiliMessageFormatter
                .formatSubscriptionResult(result);
            System.out.println(
                "[BILIBILI] URL subscription processed " + link);
        } catch (Exception e) {
            reply = formatter.formatResolveFailure(e.getMessage());
            System.err.println("[BILIBILI] URL subscription failed "
                + link + ": " + e.getMessage());
        }
        safeSend(from, reply);
    }

    @Override
    public int priority() {
        return 40;
    }

    private void safeSend(String to, String text) {
        try {
            outboundGateway.sendText(to, text);
        } catch (Exception e) {
            System.err.println("[BILIBILI] send failed: " + e.getMessage());
        }
    }

    private String firstBilibiliLink(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = BILIBILI_LINK.matcher(text);
        return matcher.find() ? trimTrailingPunctuation(matcher.group(1)) : null;
    }

    private String trimTrailingPunctuation(String link) {
        String trimmed = link == null ? "" : link.trim();
        while (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (Character.isLetterOrDigit(last) || last == '/' || last == '=') break;
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private String extractText(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) return null;
        StringBuilder text = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                text.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem voice = item.getVoice_item();
                if (voice.getText() != null
                    && !voice.getText().isBlank()) {
                    text.append(voice.getText());
                }
            }
        }
        return text.toString();
    }
}
