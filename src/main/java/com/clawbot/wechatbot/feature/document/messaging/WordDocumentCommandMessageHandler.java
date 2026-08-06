package com.clawbot.wechatbot.feature.document.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.document.application.WordDocumentCommandService;
import com.clawbot.wechatbot.feature.document.model.WordDocumentEditResult;
import com.clawbot.wechatbot.feature.document.model.WordDocumentSession;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

/** 处理已上传 Word 文档后的多轮文本编辑命令。 */
@Component
public class WordDocumentCommandMessageHandler implements MessageHandler {
    private final WordDocumentCommandService documents;
    private final PendingWordDocumentInstructionStore pendingInstructions;

    public WordDocumentCommandMessageHandler(
        WordDocumentCommandService documents,
        PendingWordDocumentInstructionStore pendingInstructions
    ) {
        this.documents = documents;
        this.pendingInstructions = pendingInstructions;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        String userId = msg == null ? null : msg.getFrom_user_id();
        String text = extractText(msg);
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        if (WordDocumentCommandParser.looksLikeWordDocumentCommandBatch(text)) return true;
        if (documents.hasActiveSession(userId)) return looksLikeNaturalWordEdit(text);
        return looksLikeWordFileEditRequest(text);
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String userId = msg.getFrom_user_id();
        String text = extractText(msg);
        WordDocumentCommandParser.ParsedCommand command =
            WordDocumentCommandParser.parse(text);
        if (command.type() != WordDocumentCommandParser.CommandType.HELP
            && !documents.hasActiveSession(userId)) {
            String pendingInstruction =
                WordDocumentCommandParser.extractPendingFileInstruction(text);
            if (pendingInstruction != null) {
                pendingInstructions.put(userId, pendingInstruction);
                sendText(client, userId,
                    "已记下这一次的 Word 修改需求，请在 3 分钟内发送 .docx 文件。");
            } else {
                sendText(client, userId,
                    "当前没有可编辑的 Word 文档，请先发送 .docx 文件。"
                        + "如果要先发需求，可以说：等下发个文档，帮我美化排版。");
            }
            return;
        }
        pendingInstructions.clear(userId);
        WordDocumentEditResult result = documents.handle(userId, text);
        sendText(client, userId, result.message());
        if (result.success() && result.shouldSendFile()) {
            sendWord(client, userId, result.session());
        }
    }

    private boolean looksLikeNaturalWordEdit(String text) {
        return text.contains("文档") || text.contains("Word") || text.contains("word")
            || text.contains("字体") || text.contains("字号") || text.contains("居中")
            || text.contains("排版") || text.contains("导出") || text.contains("修改")
            || text.contains("标题") || text.contains("正文") || text.contains("段落")
            || text.contains("行距") || text.contains("缩进") || text.contains("加粗")
            || text.contains("对齐") || text.contains("替换") || text.contains("删掉")
            || text.contains("删除") || text.contains("加一段") || text.contains("另起一页")
            || text.contains("正式一点") || text.contains("好看一点")
            || text.contains("规范一下");
    }

    private boolean looksLikeWordFileEditRequest(String text) {
        boolean mentionsWord = text.contains("文档") || text.contains("Word")
            || text.contains("word") || text.contains(".docx");
        boolean mentionsEdit = text.contains("修改") || text.contains("编辑")
            || text.contains("字体") || text.contains("字号") || text.contains("居中")
            || text.contains("排版") || text.contains("导出");
        return mentionsWord && mentionsEdit;
    }

    @Override
    public int priority() {
        return 35;
    }

    private void sendWord(ILinkClient client, String userId, WordDocumentSession session) {
        if (session == null) return;
        try {
            client.sendFile(
                userId,
                session.getContent(),
                session.getFileName(),
                "Word 文档：" + session.getFileName());
        } catch (Exception e) {
            sendText(client, userId, "文档导出失败：" + e.getMessage());
        }
    }

    private String extractText(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) return null;
        StringBuilder out = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                out.append(item.getText_item().getText());
            } else if (item.getVoice_item() != null) {
                VoiceItem voice = item.getVoice_item();
                if (voice.getText() != null) out.append(voice.getText());
            }
        }
        return out.toString().trim();
    }

    private void sendText(ILinkClient client, String userId, String text) {
        try {
            client.sendTextWithTyping(
                userId, text, Math.min(2000, 300L + text.length() * 20L));
        } catch (Exception e) {
            System.err.println("[WORD-DOC] 发送消息失败：" + e.getMessage());
        }
    }
}
