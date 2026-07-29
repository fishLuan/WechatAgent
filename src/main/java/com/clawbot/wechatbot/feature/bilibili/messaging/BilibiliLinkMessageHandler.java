package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 优先拦截 B 站链接，避免进入大模型网页搜索循环。 */
public class BilibiliLinkMessageHandler implements MessageHandler {
    private static final Pattern BILIBILI_LINK = Pattern.compile(
        "(?i)((?:https?://)?(?:(?:[a-z0-9-]+\\.)?bilibili\\.com|b23\\.tv)/\\S+)");

    private final BilibiliContentSource contentSource;
    private final BilibiliMessageFormatter formatter;

    public BilibiliLinkMessageHandler(
        BilibiliContentSource contentSource,
        BilibiliMessageFormatter formatter
    ) {
        this.contentSource = contentSource;
        this.formatter = formatter;
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
        if (link == null) return;

        try {
            String reply = formatter.formatResolvedContent(contentSource.resolveUrl(link));
            client.sendTextWithTyping(from, reply, typingMillis(reply));
            System.out.println("[BILIBILI] resolved " + link);
        } catch (Exception e) {
            String reply = formatter.formatResolveFailure(e.getMessage());
            safeSend(client, from, reply);
            System.err.println("[BILIBILI] resolve failed " + link + ": " + e.getMessage());
        }
    }

    @Override
    public int priority() {
        return 40;
    }

    private void safeSend(ILinkClient client, String to, String text) {
        try {
            client.sendTextWithTyping(to, text, typingMillis(text));
        } catch (Exception e) {
            System.err.println("[BILIBILI] send failed: " + e.getMessage());
        }
    }

    private long typingMillis(String text) {
        return Math.min(2000, 300L + (text == null ? 0 : text.length()) * 20L);
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
            }
        }
        return text.toString();
    }
}
