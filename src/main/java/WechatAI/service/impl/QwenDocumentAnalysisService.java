package WechatAI.service.impl;

import WechatAI.service.AiChatService;
import WechatAI.service.DocumentAnalysisService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * 基于本地文本抽取和千问文本模型的文档分析实现。
 */
public class QwenDocumentAnalysisService implements DocumentAnalysisService {

    private static final int MAX_DOCUMENT_TEXT_CHARS = 12000;

    private final AiChatService aiChatService;
    private String lastErrorMessage;

    public QwenDocumentAnalysisService(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @Override
    public boolean supports(String fileName) {
        String normalized = normalizeFileName(fileName);
        return normalized.endsWith(".pdf")
                || normalized.endsWith(".docx")
                || normalized.endsWith(".doc");
    }

    @Override
    public String analyze(byte[] fileBytes, String fileName, String prompt) {
        lastErrorMessage = null;
        if (fileBytes == null || fileBytes.length == 0) {
            setLastErrorMessage("文档文件为空。");
            return null;
        }
        if (!supports(fileName)) {
            setLastErrorMessage("暂时只支持 PDF、DOC、DOCX 文档。");
            return null;
        }

        try {
            String documentText = extractText(fileBytes, fileName);
            if (documentText == null || documentText.trim().isEmpty()) {
                setLastErrorMessage("没有从文档中提取到文字。若这是扫描版 PDF，需要后续接入 OCR。");
                return null;
            }

            String summaryPrompt = buildSummaryPrompt(fileName, documentText, prompt);
            return aiChatService.chat(summaryPrompt);
        } catch (Exception e) {
            setLastErrorMessage("文档分析失败：" + e.getMessage());
            return null;
        }
    }

    @Override
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private String extractText(byte[] fileBytes, String fileName) throws Exception {
        String normalized = normalizeFileName(fileName);
        if (normalized.endsWith(".pdf")) {
            return extractPdfText(fileBytes);
        }
        if (normalized.endsWith(".docx")) {
            return extractDocxText(fileBytes);
        }
        if (normalized.endsWith(".doc")) {
            return extractDocText(fileBytes);
        }
        return "";
    }

    private String extractPdfText(byte[] fileBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractDocxText(byte[] fileBytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDocText(byte[] fileBytes) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(fileBytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String buildSummaryPrompt(String fileName, String documentText, String userPrompt) {
        String clippedText = clip(documentText);
        String instruction = userPrompt == null || userPrompt.trim().isEmpty()
                ? "请识别并分析总结这份文档。"
                : userPrompt.trim();

        return "你是一个专业的文档分析助手。请根据用户要求分析下面的文档内容。\n\n"
                + "用户要求：" + instruction + "\n"
                + "文件名：" + fileName + "\n\n"
                + "请用中文输出，结构如下：\n"
                + "1. 文档主题\n"
                + "2. 核心结论\n"
                + "3. 关键要点\n"
                + "4. 重要数据或条款\n"
                + "5. 风险、待办或建议\n\n"
                + "文档正文：\n"
                + clippedText;
    }

    private String clip(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_DOCUMENT_TEXT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_DOCUMENT_TEXT_CHARS)
                + "\n\n[文档较长，以上为前 " + MAX_DOCUMENT_TEXT_CHARS + " 个字符，建议后续接入分段总结。]";
    }

    private String normalizeFileName(String fileName) {
        return fileName == null ? "" : fileName.toLowerCase(Locale.ROOT).trim();
    }

    private void setLastErrorMessage(String message) {
        lastErrorMessage = message;
        System.err.println("❌ " + message);
    }
}
