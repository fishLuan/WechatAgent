package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.base.PlanningBypassMessageHandler;
import com.clawbot.wechatbot.intent.IntentRecognizer;
import com.clawbot.wechatbot.intent.IntentResult;
import com.clawbot.wechatbot.intent.IntentType;
import com.clawbot.wechatbot.intent.ConversationDomainStore;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

/**
 * B站结构化命令入口。能确定的命令直接执行，无法确定的文本继续交给普通Agent。
 */
@Component
public final class BilibiliCommandMessageHandler implements PlanningBypassMessageHandler {
    private final BilibiliCommandHandler commands;
    private final WeChatOutboundGateway gateway;
    private final IntentRecognizer intents;
    private final ConversationDomainStore domains;

    public BilibiliCommandMessageHandler(
        BilibiliCommandHandler commands,
        WeChatOutboundGateway gateway,
        IntentRecognizer intents,
        ConversationDomainStore domains
    ) {
        this.commands = commands;
        this.gateway = gateway;
        this.intents = intents;
        this.domains = domains;
    }

    @Override
    public boolean canHandle(WeixinMessage message) {
        String text = WeChatMessageTextExtractor.extract(message).trim();
        if (text.isEmpty()) return false;
        if (isBareIndex(text)) {
            return domains.isActive(message.getFrom_user_id(),
                ConversationDomainStore.Domain.BILIBILI);
        }
        IntentResult intent = intents.recognize(text);
        if (intent.type() == IntentType.WEREAD_QUERY) return false;
        // 定时/预约推送请求（含时间词+推送）交给通用 Agent（走 scheduler_manage 创建定时任务），本处理器不拦
        if (looksLikeScheduledPush(text)) return false;
        if (BilibiliCommandParser.parse(text).type()
            != BilibiliCommandParser.CmdType.UNKNOWN) {
            return true;
        }
        return intent.isBilibiliIntent();
    }

    @Override
    public boolean canBypassPlanning(WeixinMessage message) {
        String text = WeChatMessageTextExtractor.extract(message).trim();
        if (text.isEmpty() || looksLikeScheduledPush(text) || looksLikeMultipleTasks(text)) {
            return false;
        }
        if (isBareIndex(text)) {
            return domains.isActive(message.getFrom_user_id(),
                ConversationDomainStore.Domain.BILIBILI);
        }
        IntentResult intent = intents.recognize(text);
        return intent.type() != IntentType.WEREAD_QUERY
            && (BilibiliCommandParser.parse(text).type()
                != BilibiliCommandParser.CmdType.UNKNOWN
                || intent.isBilibiliIntent());
    }

    private boolean looksLikeMultipleTasks(String text) {
        return text.matches(".*(?:然后|并且|同时|另外|接着|顺便).+");
    }

    /** 定时推送请求检测：时间词 + 推送/推荐/提醒 语义 */
    private boolean looksLikeScheduledPush(String text) {
        boolean hasTime = text.matches(".*(每天|每日|明天|后天|定时|预约|几点|固定时间|\\d+\\s*点|\\d{1,2}[:：]\\d{2}).*");
        boolean hasPush = text.matches(".*(推送|推荐|提醒|发给我|发一下).*");
        return hasTime && hasPush;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage message) {
        if (message == null) return;
        String userId = message.getFrom_user_id();
        String text = WeChatMessageTextExtractor.extract(message).trim();
        if (userId == null || userId.isBlank() || text.isEmpty()) return;
        domains.activate(userId, ConversationDomainStore.Domain.BILIBILI);

        if (isBareIndex(text)) {
            String reply = commands.handleSearchResultByIndex(
                userId, Integer.parseInt(text));
            gateway.sendText(userId, reply);
            System.out.println("[SEND-BILIBILI] " + summarize(reply));
            return;
        }

        BilibiliCommandParser.ParsedCommand parsed =
            BilibiliCommandParser.parse(text);
        String reply;
        if (parsed.type() != BilibiliCommandParser.CmdType.UNKNOWN) {
            reply = routeParsed(userId, text, parsed);
        } else {
            reply = routeRecognizedIntent(userId, text, intents.recognize(text));
        }
        if (reply == null || reply.isBlank()
            || reply.startsWith("[UNHANDLED-BILIBILI-UNKNOWN]")) {
            return;
        }
        gateway.sendText(userId, reply);
        System.out.println("[SEND-BILIBILI] " + summarize(reply));
    }

    @Override
    public int priority() {
        return 50;
    }

    private String routeParsed(
        String userId,
        String original,
        BilibiliCommandParser.ParsedCommand parsed
    ) {
        return switch (parsed.type()) {
            case SUBSCRIBE_BY_TITLE ->
                commands.handleSubscribeByTitle(userId, parsed.title());
            case SEARCH_BY_TITLE ->
                commands.handleSearchByTitle(userId, parsed.title());
            case MARK_TITLE ->
                commands.handleMarkStateByTitle(
                    userId, parsed.title(), parsed.state());
            default -> commands.handle(userId, original);
        };
    }

    private String routeRecognizedIntent(
        String userId, String original, IntentResult intent
    ) {
        return switch (intent.type()) {
            case BILIBILI_SUBSCRIBE_TITLE ->
                commands.handleSubscribeByTitle(userId, intent.slot("title"));
            case BILIBILI_SEARCH_TITLE ->
                commands.handleSearchByTitle(userId, intent.slot("title"));
            case BILIBILI_MARK_TITLE ->
                commands.handleMarkStateByTitle(
                    userId, intent.slot("title"), intent.slot("state"));
            case BILIBILI_RECOMMEND -> commands.handleTodayRecommend(
                userId, contentType(intent.slot("content_type")), null);
            default -> intent.type() == IntentType.GENERAL_CHAT
                ? "[UNHANDLED-BILIBILI-UNKNOWN]"
                : commands.handle(userId, original);
        };
    }

    private ContentType contentType(String value) {
        try {
            return ContentType.valueOf(value == null ? "BANGUMI" : value);
        } catch (IllegalArgumentException ignored) {
            return ContentType.BANGUMI;
        }
    }

    private boolean isBareIndex(String text) {
        return text != null && text.matches("(?:[1-9]|1\\d|20)");
    }

    private String summarize(String reply) {
        String summary = reply.replace('\r', ' ').replace('\n', ' ');
        return summary.length() <= 200 ? summary : summary.substring(0, 200) + "...";
    }
}
