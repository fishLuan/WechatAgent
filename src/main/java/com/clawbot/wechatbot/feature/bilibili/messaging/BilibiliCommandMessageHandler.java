package com.clawbot.wechatbot.feature.bilibili.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.intent.IntentRecognizer;
import com.clawbot.wechatbot.intent.IntentResult;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class BilibiliCommandMessageHandler implements MessageHandler {

    private final BilibiliCommandHandler commandHandler;
    private final WeChatOutboundGateway outboundGateway;
    private final IntentRecognizer intentRecognizer;

    public BilibiliCommandMessageHandler(
        @Lazy BilibiliCommandHandler commandHandler,
        WeChatOutboundGateway outboundGateway,
        IntentRecognizer intentRecognizer
    ) {
        this.commandHandler = commandHandler;
        this.outboundGateway = outboundGateway;
        this.intentRecognizer = intentRecognizer;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (msg == null) return false;
        String text = extractText(msg);
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isBlank()) return false;
        IntentResult intent = intentRecognizer.recognize(trimmed);
        if (intent.isBilibiliIntent()) return true;
        BilibiliCommandParser.ParsedCommand cmd =
            BilibiliCommandParser.parse(trimmed);
        return cmd != null && cmd.type() != BilibiliCommandParser.CmdType.UNKNOWN;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        String rawText = extractText(msg);
        String text = rawText == null ? "" : rawText.trim();
        if (from == null || from.isBlank() || text.isBlank()) return;

        System.out.println("[RECV-BILIBILI] <" + from + "> " + text);

        String reply;
        try {
            IntentResult intent = intentRecognizer.recognize(text);
            System.out.println("[INTENT] type=" + intent.type()
                + " confidence="
                + String.format("%.2f", intent.confidence())
                + " slots=" + intent.slots());
            reply = switch (intent.type()) {
                case BILIBILI_SUBSCRIBE_TITLE ->
                    commandHandler.handleSubscribeByTitle(
                        from, intent.slot("title"));
                case BILIBILI_SEARCH_TITLE ->
                    commandHandler.handleSearchByTitle(
                        from, intent.slot("title"));
                case BILIBILI_MARK_TITLE ->
                    commandHandler.handleMarkStateByTitle(
                        from,
                        intent.slot("title"),
                        intent.slot("state"));
                default -> commandHandler.handle(from, text);
            };
        } catch (Exception e) {
            reply = "❌ B站命令处理失败：" + e.getMessage();
            System.err.println("[BILIBILI-HANDLER] 处理失败: " + e.getMessage());
            e.printStackTrace();
        }

        if (reply == null || reply.isBlank() || reply.startsWith("【UNHANDLED-BILIBILI-UNKNOWN】")) {
            return;
        }

        try {
            outboundGateway.sendText(from, reply);
            String shortReply = reply.replace("\r", " ").replace("\n", " | ");
            if (shortReply.length() > 200) shortReply = shortReply.substring(0, 200) + "...";
            System.out.println("[SEND-BILIBILI] " + shortReply);
        } catch (Exception e) {
            System.err.println("[BILIBILI-HANDLER] 回复发送失败: " + e.getMessage());
        }
    }

    private String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem v = item.getVoice_item();
                if (v.getText() != null && !v.getText().isEmpty()) {
                    sb.append(v.getText());
                }
            }
        }
        return sb.toString();
    }
}
