package com.clawbot.wechatbot.service;

import com.clawbot.wechatbot.service.document.PdfDocumentService;
import com.clawbot.wechatbot.service.document.WordDocumentService;
import com.clawbot.wechatbot.service.support.PdfLogSilencer;

import java.nio.charset.StandardCharsets;

/** 文档门面：只负责格式判断和路由，具体 PDF/Word 逻辑由独立服务完成。 */
public class DocumentService {
    private final PdfDocumentService pdfService;
    private final WordDocumentService wordService;

    public DocumentService() {
        this(new PdfDocumentService(), new WordDocumentService());
    }

    public DocumentService(PdfDocumentService pdfService, WordDocumentService wordService) {
        this.pdfService = pdfService;
        this.wordService = wordService;
    }

    public static void silencePdfLogs() {
        PdfLogSilencer.silence();
    }

    public boolean isPdf(String fileName) {
        return hasExtension(fileName, ".pdf");
    }

    public boolean isWord(String fileName) {
        return hasExtension(fileName, ".docx") || hasExtension(fileName, ".doc");
    }

    public boolean isText(String fileName) {
        return hasExtension(fileName, ".txt");
    }

    public String extractText(byte[] fileBytes, String fileName) throws Exception {
        if (fileBytes == null) throw new IllegalArgumentException("文件内容不能为空");
        if (isPdf(fileName)) return pdfService.extractText(fileBytes);
        if (isWord(fileName)) return wordService.extractText(fileBytes, fileName);
        if (isText(fileName)) return new String(fileBytes, StandardCharsets.UTF_8);
        throw new IllegalArgumentException("不支持的文件类型：" + fileName + "（仅支持 PDF/Word/TXT）");
    }

    public byte[] createPdf(String title, String content) throws Exception {
        return pdfService.create(title, content);
    }

    public byte[] createWord(String title, String content) throws Exception {
        return wordService.create(title, content);
    }

    private boolean hasExtension(String fileName, String extension) {
        return fileName != null && fileName.toLowerCase().endsWith(extension);
    }
}
