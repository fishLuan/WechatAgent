package com.luanxv.pre.wechatAI.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.luanxv.pre.wechatAI.core.ConversationMemoryService;
import com.luanxv.pre.wechatAI.model.MessageContext;
import com.luanxv.pre.wechatAI.service.QwenService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Downloads supported WeChat file messages and gives their extracted text to the chat model. */
public class FileMessageHandler implements MessageTypeHandler {
    private static final int MAX_DOCUMENT_CHARS = 24_000;

    private final ILinkClient client;
    private final QwenService qwenService;
    private final ConversationMemoryService memoryService;

    public FileMessageHandler(ILinkClient client, QwenService qwenService,
                              ConversationMemoryService memoryService) {
        this.client = client;
        this.qwenService = qwenService;
        this.memoryService = memoryService;
    }

    @Override
    public boolean supports(MessageContext context) {
        return context.isHasFile();
    }

    @Override
    public String handle(MessageContext context) {
        MessageItem item = context.getFileItem();
        FileItem file = item == null ? null : item.getFile_item();
        String fileName = file == null || file.getFile_name() == null ? "document" : file.getFile_name();
        String extension = extensionOf(fileName);
        if (!isSupported(extension)) {
            return "暂时支持 PDF、DOCX、TXT 和 MD 文件，请将该文件转换后再发送。";
        }

        try {
            sendProgress(context.getFromUserId(), "文件解析中，请等待…");
            byte[] bytes = downloadVerifiedFile(item, file, extension);
            String documentText = extract(bytes, extension);
            if (documentText.isBlank()) {
                return "没有从文件中提取到可读文字。若这是扫描版 PDF，请先进行 OCR 后再发送。";
            }
            boolean truncated = documentText.length() > MAX_DOCUMENT_CHARS;
            String usableText = truncated ? documentText.substring(0, MAX_DOCUMENT_CHARS) : documentText;
            String request = context.getTextContent();
            if (request == null || request.isBlank()) {
                request = "请用简洁的中文总结这个文件，列出重点、结论和需要注意的事项。";
            }
            String modelInput = "用户上传了文件《" + fileName + "》。\n"
                    + "用户要求：" + request + "\n\n"
                    + "以下是文件提取的正文" + (truncated ? "（过长，仅前 24000 字符）" : "") + "：\n"
                    + usableText;
            String memorySummary = "[文件] " + fileName + "：" + request;
            String reply = qwenService.chatDocument(context.getFromUserId(), modelInput, memorySummary);
            if (reply == null || reply.isBlank()) {
                return "文件已解析，但暂时无法生成回答，请稍后再试。";
            }
            return reply;
        } catch (IOException exception) {
            System.err.println("文件下载或解析失败: " + exception.getMessage());
            return "文件读取失败，请确认文件没有损坏后重新发送。";
        } catch (Exception exception) {
            System.err.println("文件处理失败: " + exception.getMessage());
            return "文件处理时出现问题，请稍后重试。";
        }
    }

    private String extract(byte[] bytes, String extension) throws IOException {
        return switch (extension) {
            case "pdf" -> extractPdf(bytes);
            case "docx" -> extractDocx(bytes);
            case "txt", "md" -> new String(bytes, StandardCharsets.UTF_8);
            default -> "";
        };
    }

    /**
     * The CDN response is encrypted by WeChat. Do not send an error page or an incomplete
     * download to PDFBox/POI: verify the decrypted bytes against the message metadata first.
     */
    private byte[] downloadVerifiedFile(MessageItem item, FileItem file, String extension) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                byte[] bytes = client.downloadFileFromMessageItem(item);
                if (bytes == null || bytes.length == 0) {
                    throw new IOException("downloaded file is empty");
                }
                String expectedMd5 = file.getMd5();
                String actualMd5 = md5(bytes);
                if (expectedMd5 != null && !expectedMd5.isBlank() && !expectedMd5.equalsIgnoreCase(actualMd5)) {
                    throw new IOException("downloaded bytes do not match file MD5 (expected="
                            + expectedMd5 + ", actual=" + actualMd5 + ")");
                }
                if (!hasExpectedHeader(bytes, extension)) {
                    throw new IOException("downloaded content does not match ." + extension
                            + " file header; size=" + bytes.length + ", firstBytes=" + firstBytes(bytes));
                }
                return bytes;
            } catch (IOException exception) {
                lastFailure = exception;
                System.err.printf("文件下载校验失败（第 %d/3 次）: %s%n", attempt, exception.getMessage());
            }
        }
        throw new IOException("微信文件下载内容异常，已重试 3 次。" + (lastFailure == null ? "" : lastFailure.getMessage()), lastFailure);
    }

    private boolean hasExpectedHeader(byte[] bytes, String extension) {
        if (extension.equals("pdf")) {
            return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                    && bytes[3] == 'F' && bytes[4] == '-';
        }
        if (extension.equals("docx")) {
            return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                    && bytes[2] == 3 && bytes[3] == 4;
        }
        return true;
    }

    private String md5(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("MD5 algorithm is unavailable", exception);
        }
    }

    private String firstBytes(byte[] bytes) {
        int length = Math.min(bytes.length, 32);
        return java.util.HexFormat.of().formatHex(bytes, 0, length);
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private void sendProgress(String userId, String message) {
        try {
            client.sendText(userId, message);
        } catch (IOException exception) {
            System.err.println("文件进度消息发送失败: " + exception.getMessage());
        }
    }

    private boolean isSupported(String extension) {
        return extension.equals("pdf") || extension.equals("docx")
                || extension.equals("txt") || extension.equals("md");
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
