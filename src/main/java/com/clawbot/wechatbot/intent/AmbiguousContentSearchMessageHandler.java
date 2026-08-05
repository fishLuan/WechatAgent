package com.clawbot.wechatbot.intent;

import com.clawbot.wechatbot.base.PlanningBypassMessageHandler;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import com.clawbot.wechatbot.feature.weread.WereadCommandHandler;
import com.clawbot.wechatbot.feature.weread.WereadProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

/** Resolves an unspecified work title before routing to books or Bilibili. */
@Component
public final class AmbiguousContentSearchMessageHandler
    implements PlanningBypassMessageHandler {

    private final IntentRecognizer intents;
    private final WebSearchContentDomainResolver resolver;
    private final PendingContentDomainChoiceStore pendingChoices;
    private final ConversationDomainStore domains;
    private final WereadCommandHandler weread;
    private final WereadProperties wereadProperties;
    private final BilibiliCommandHandler bilibili;

    public AmbiguousContentSearchMessageHandler(
        IntentRecognizer intents,
        WebSearchContentDomainResolver resolver,
        PendingContentDomainChoiceStore pendingChoices,
        ConversationDomainStore domains,
        WereadCommandHandler weread,
        WereadProperties wereadProperties,
        BilibiliCommandHandler bilibili
    ) {
        this.intents = intents;
        this.resolver = resolver;
        this.pendingChoices = pendingChoices;
        this.domains = domains;
        this.weread = weread;
        this.wereadProperties = wereadProperties;
        this.bilibili = bilibili;
    }

    @Override
    public boolean canHandle(WeixinMessage message) {
        String text = extract(message).trim();
        if (text.isEmpty()) return false;
        if (pendingChoices.get(message.getFrom_user_id()) != null
            && selectedDomain(text) != null) return true;
        return intents.recognize(text).type() == IntentType.CONTENT_SEARCH_AMBIGUOUS;
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
            ContentDomainResolution.Domain selected = selectedDomain(text);
            PendingContentDomainChoiceStore.PendingChoice pending =
                pendingChoices.get(userId);
            if (pending != null && selected != null) {
                pendingChoices.consume(userId);
                send(client, userId, execute(userId, pending.title(), selected));
                return;
            }

            IntentResult intent = intents.recognize(text);
            String title = intent.slot("title");
            if (title == null || title.isBlank()) {
                send(client, userId, "请提供具体作品名称，并说明要找书籍还是影视作品。");
                return;
            }
            ContentDomainResolution resolution = resolver.resolve(title);
            switch (resolution.domain()) {
                case BOOK -> send(client, userId, execute(
                    userId, title, ContentDomainResolution.Domain.BOOK));
                case BILIBILI -> send(client, userId, execute(
                    userId, title, ContentDomainResolution.Domain.BILIBILI));
                case BOTH -> askForChoice(client, userId, title,
                    "这个名称同时存在书籍和影视作品");
                case UNKNOWN -> askForChoice(client, userId, title,
                    "暂时无法准确判断作品类型");
            }
        } catch (Exception error) {
            send(client, userId, "作品类型判断失败。请回复“书籍”或“影视作品”后重试。");
        }
    }

    private String execute(
        String userId, String title, ContentDomainResolution.Domain domain
    ) throws Exception {
        if (domain == ContentDomainResolution.Domain.BOOK) {
            domains.activate(userId, ConversationDomainStore.Domain.WEREAD);
            if (!wereadProperties.hasApiKey()) {
                return "微信读书未配置：请先设置 WEREAD_API_KEY 并重启程序。";
            }
            return weread.handle("搜一下 " + title);
        }
        domains.activate(userId, ConversationDomainStore.Domain.BILIBILI);
        return bilibili.handleSearchByTitle(userId, title);
    }

    private void askForChoice(
        ILinkClient client, String userId, String title, String reason
    ) {
        pendingChoices.put(userId, title);
        send(client, userId, reason + "：《" + title + "》。你想找哪一种？\n\n"
            + "1. 书籍\n2. 影视作品\n\n回复“1/书籍”或“2/影视”即可继续。" );
    }

    private ContentDomainResolution.Domain selectedDomain(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.matches("1|书籍|书|小说|原著|找书|搜书")) {
            return ContentDomainResolution.Domain.BOOK;
        }
        if (normalized.matches("2|影视|影视作品|电影|电视剧|动漫|动画|番剧|搜影视")) {
            return ContentDomainResolution.Domain.BILIBILI;
        }
        return null;
    }

    private void send(ILinkClient client, String userId, String reply) {
        try {
            client.sendText(userId, reply);
            System.out.println("[SEND-CONTENT-ROUTER] " + reply.replace('\n', ' '));
        } catch (Exception error) {
            System.err.println("[CONTENT-ROUTER] 发送失败：" + error.getMessage());
        }
    }

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

    @Override
    public int priority() { return 40; }
}
