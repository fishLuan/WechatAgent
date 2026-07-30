package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.intent.IntentRecognizer;
import com.clawbot.wechatbot.intent.IntentResult;
import com.clawbot.wechatbot.intent.IntentType;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

/**
 * B站结构化命令入口。能确定的命令直接执行，无法确定的文本继续交给普通Agent。
 */
@Component
public final class BilibiliCommandMessageHandler implements MessageHandler {
    private final BilibiliCommandHandler commands;
    private final WeChatOutboundGateway gateway;
    private final IntentRecognizer intents;

    public BilibiliCommandMessageHandler(
        BilibiliCommandHandler commands,
        WeChatOutboundGateway gateway,
        IntentRecognizer intents
    ) {
        this.commands = commands;
        this.gateway = gateway;
        this.intents = intents;
    }

    @Override
    public boolean canHandle(WeixinMessage message) {
        String text = BilibiliLinkMessageHandler.extractText(message).trim();
        if (text.isEmpty()) return false;
        if (BilibiliCommandParser.parse(text).type()
            != BilibiliCommandParser.CmdType.UNKNOWN) {
            return true;
        }
        return intents.recognize(text).isBilibiliIntent();
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage message) {
        if (message == null) return;
        String userId = message.getFrom_user_id();
        String text = BilibiliLinkMessageHandler.extractText(message).trim();
        if (userId == null || userId.isBlank() || text.isEmpty()) return;

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
            case BILIBILI_RECOMMEND -> commands.handle(userId, original);
            default -> intent.type() == IntentType.GENERAL_CHAT
                ? "[UNHANDLED-BILIBILI-UNKNOWN]"
                : commands.handle(userId, original);
        };
    }

    private String summarize(String reply) {
        String summary = reply.replace('\r', ' ').replace('\n', ' ');
        return summary.length() <= 200 ? summary : summary.substring(0, 200) + "...";
    }
}
