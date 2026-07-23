package com.clawbot.wechatbot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.clawbot.wechatbot.base.MessageHandler;
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

    public DocumentMessageHandler(ChatService chatService, DocumentService documentService) {
        this.chatService = chatService;
        this.documentService = documentService;
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
        safeSendText(client, from, "📄 收到文件：" + fileName + "，正在帮你读取并总结...");

        try {
            // 1. 从消息下载文件
            MessageItem msgItem = findFileMessageItem(msg);
            byte[] fileBytes = (msgItem != null) ? client.downloadFileFromMessageItem(msgItem) : null;
            if (fileBytes == null || fileBytes.length == 0) {
                safeSendText(client, from, "❌ 文件下载失败，可能是网络问题，重试一下？");
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
            safeSendText(client, from, "❌ 处理文件时出错：" + e.getMessage()
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

    @Override
    public int priority() {
        return 30;  // 高于 TextMessageHandler(100)，文件消息优先到这里
    }
}