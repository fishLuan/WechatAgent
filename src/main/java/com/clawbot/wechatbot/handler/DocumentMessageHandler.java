package com.clawbot.wechatbot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.document.application.WordDocumentCommandService;
import com.clawbot.wechatbot.feature.document.messaging.PendingWordDocumentInstructionStore;
import com.clawbot.wechatbot.feature.document.messaging.WordDocumentCommandParser;
import com.clawbot.wechatbot.feature.document.model.WordDocumentEditResult;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;

/**
 * 文档消息处理器：
 *   检测用户发来的 PDF / Word / TXT 文件 → 自动提取文本 → 调用大模型总结 → 回复文字
 *
 * 优先级：高于 TextMessageHandler，文件消息先到这里处理。
 */
public class DocumentMessageHandler implements MessageHandler {

    private final ChatService chatService;
    private final DocumentService documentService;
    private final WordDocumentCommandService wordDocuments;
    private final PendingWordDocumentInstructionStore pendingWordInstructions;

    public DocumentMessageHandler(
        ChatService chatService,
        DocumentService documentService,
        WordDocumentCommandService wordDocuments,
        PendingWordDocumentInstructionStore pendingWordInstructions
    ) {
        this.chatService = chatService;
        this.documentService = documentService;
        this.wordDocuments = wordDocuments;
        this.pendingWordInstructions = pendingWordInstructions;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            FileItem fi = item.getFile_item();
            if (fi != null) {
                String fn = fi.getFile_name();
                if (documentService.isPdf(fn)
                        || documentService.isWord(fn)
                        || documentService.isText(fn)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();

        // 找到文件消息项
        FileItem fileItem = null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) {
                fileItem = item.getFile_item();
                break;
            }
        }
        if (fileItem == null) return;

        String fileName = fileItem.getFile_name();
        System.out.println("[WORD-DOC] 收到文件：" + fileName);
        boolean editableWord = fileName != null && fileName.toLowerCase().endsWith(".docx");
        if (!editableWord) {
            safeSendText(client, from, "📄 收到文件：" + fileName + "，正在读取...");
        }

        try {
            // 1. 从消息下载文件
            MessageItem msgItem = findFileMessageItem(msg);
            byte[] fileBytes = (msgItem != null) ? client.downloadFileFromMessageItem(msgItem) : null;
            if (fileBytes == null || fileBytes.length == 0) {
                safeSendText(client, from, "文件下载失败，请稍后重试。");
                return;
            }
            if (editableWord) {
                System.out.println("[WORD-DOC] 创建 Word 编辑会话：" + fileName);
                WordDocumentEditResult result =
                    wordDocuments.createSession(from, fileName, fileBytes);
                if (result.success()) {
                    String pendingInstruction = pendingWordInstructions.takeLatest(
                        from, this::looksLikeWordEditInstruction);
                    if (pendingInstruction != null) {
                        WordDocumentEditResult edited =
                            wordDocuments.applyInstruction(from, pendingInstruction);
                        if (edited.success()) {
                            safeSendText(client, from,
                                edited.message() + "\n正在回传修改后的 Word 文档。");
                            sendWord(client, from, edited);
                        } else {
                            safeSendText(client, from,
                                wordDocuments.createdMessage(result.session())
                                    + "\n\n自动应用刚才的需求失败："
                                    + edited.message());
                        }
                        return;
                    }
                    safeSendText(client, from,
                        wordDocuments.createdMessage(result.session()));
                } else {
                    safeSendText(client, from, result.message());
                }
                return;
            }
            // 2. 提取文本
            String text = documentService.extractText(fileBytes, fileName);
            if (text == null || text.trim().isEmpty()) {
                safeSendText(client, from, "⚠️ 这个文件是空的或无法提取文本。");
                return;
            }

            // 3. 太长就截断（DeepSeek 有 token 限制），让大模型做总结
            String textForModel = text;
            boolean truncated = false;
            if (text.length() > 8000) {
                textForModel = text.substring(0, 8000);
                truncated = true;
            }

            String prompt = "请帮我总结以下文档的核心内容，用简洁中文分点列出，突出重点：\n\n"
                    + "文件名：" + fileName + "\n"
                    + "文档内容：\n" + textForModel
                    + (truncated ? "\n\n（注：文档较长，以上是前8000字，总结时请注意）" : "");

            String summary = chatService.chat(prompt, "");

            // 4. 回复总结（微信单条消息限制，超长需分段）
            String fullReply = "📄 《" + fileName + "》总结\n\n" + summary;
            sendInChunks(client, from, fullReply);

        } catch (Exception e) {
            System.err.println("[ERROR] 处理文档失败: " + e.getMessage());
            e.printStackTrace();
            safeSendText(client, from, "文件处理失败：" + e.getMessage()
                    + "。可能是文件格式太复杂，或者已加密。");
        }
    }

    /** 从消息列表里找到带 FileItem 的 MessageItem（传给 SDK 下载方法用） */
    private MessageItem findFileMessageItem(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) return item;
        }
        return null;
    }

    /** 分段发送长文本（微信单条消息大约几千字限制，分段发送避免丢消息） */
    private void sendInChunks(ILinkClient client, String from, String text) {
        if (text == null || text.isEmpty()) return;
        int maxLen = 1500;
        if (text.length() <= maxLen) {
            safeSendText(client, from, text);
            return;
        }
        int fromIdx = 0;
        while (fromIdx < text.length()) {
            int toIdx = Math.min(fromIdx + maxLen, text.length());
            if (toIdx < text.length()) {
                int lastNewline = text.lastIndexOf('\n', toIdx);
                if (lastNewline > fromIdx + 200) {
                    toIdx = lastNewline + 1;
                }
            }
            String chunk = text.substring(fromIdx, toIdx).trim();
            if (!chunk.isEmpty()) {
                safeSendText(client, from, chunk);
            }
            fromIdx = toIdx;
        }
    }

    private void safeSendText(ILinkClient client, String from, String text) {
        try {
            if (client != null) client.sendText(from, text);
        } catch (Exception e) {
            System.err.println("[WARN] 发送文字失败: " + e.getMessage());
        }
    }

    private void sendWord(ILinkClient client, String from, WordDocumentEditResult result) {
        if (result.session() == null) return;
        try {
            client.sendFile(
                from,
                result.session().getContent(),
                result.session().getFileName(),
                "修改后的 Word 文档：" + result.session().getFileName());
        } catch (Exception e) {
            safeSendText(client, from, "Word 文档回传失败：" + e.getMessage());
        }
    }

    private boolean looksLikeWordEditInstruction(String text) {
        if (text == null || text.isBlank()) return false;
        if (WordDocumentCommandParser.looksLikeWordDocumentCommandBatch(text)) return true;
        boolean mentionsWord = text.contains("文档") || text.contains("Word")
            || text.contains("word") || text.contains(".docx");
        boolean mentionsEdit = text.contains("修改") || text.contains("编辑")
            || text.contains("字体") || text.contains("字号") || text.contains("居中")
            || text.contains("排版") || text.contains("替换") || text.contains("追加")
            || text.contains("删除") || text.contains("重命名") || text.contains("导出");
        return mentionsWord && mentionsEdit;
    }

    @Override
    public int priority() {
        return 30;  // 高于 TextMessageHandler(100)，文件消息优先到这里
    }
}
