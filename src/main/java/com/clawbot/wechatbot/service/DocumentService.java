package com.Student.wechatbot.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 文档处理服务：
 *   1. 读 PDF / Word → 提取纯文本
 *   2. 写 PDF / Word → 把对话/总结内容导出成文档
 *
 * PDF 生成采用"直接用 PDFBox 手写"方案，自动加载系统中文字体。
 */
public class DocumentService {

    // 彻底屏蔽 PDF 相关库的噪音日志（fontbox / pdfbox 每次都会打印一堆 WARN）
    static {
        silencePdfLogs();
    }

    /** 公共入口：任何地方想屏蔽 PDF 库日志，调这个方法即可 */
    public static void silencePdfLogs() {
        // 1. 设置前缀 logger 的级别（提前创建父 logger，这样子 logger 会继承）
        try {
            Logger.getLogger("org.apache.fontbox").setLevel(Level.SEVERE);
            Logger.getLogger("org.apache.pdfbox").setLevel(Level.SEVERE);
            Logger.getLogger("com.openhtmltopdf").setLevel(Level.WARNING);
            Logger.getLogger("org.docx4j").setLevel(Level.WARNING);
        } catch (Exception ignored) {}

        // 2. 遍历所有已存在的 logger
        java.util.logging.LogManager lm = java.util.logging.LogManager.getLogManager();
        Enumeration<String> names = lm.getLoggerNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith("org.apache.fontbox") || name.startsWith("org.apache.pdfbox")) {
                Logger l = lm.getLogger(name);
                if (l != null) l.setLevel(Level.SEVERE);
            }
        }

        // 3. 关键：给 root logger 的所有 handler 加 filter（无论哪个子 logger 打出来，handler 都拦）
        try {
            Filter blockPdfNoise = new Filter() {
                @Override
                public boolean isLoggable(LogRecord record) {
                    String name = record.getLoggerName();
                    if (name == null) return true;
                    if (name.startsWith("org.apache.fontbox")) return false;
                    if (name.startsWith("org.apache.pdfbox")) return false;
                    return true;
                }
            };
            Logger root = Logger.getLogger("");
            for (java.util.logging.Handler h : root.getHandlers()) {
                h.setFilter(blockPdfNoise);
            }
        } catch (Exception ignored) {}
    }

    // ================== 文件类型检测 ==================

    public boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    public boolean isWord(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".doc");
    }

    public boolean isText(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".txt");
    }

    // ================== 从文档提取纯文本 ==================

    /**
     * 从文档字节中提取纯文本内容（PDF / Word / TXT）
     * @param fileBytes 文件原始字节
     * @param fileName 文件名（用于判断类型）
     * @return 纯文本内容
     */
    public String extractText(byte[] fileBytes, String fileName) throws Exception {
        if (isPdf(fileName)) {
            return extractPdfText(fileBytes);
        } else if (isWord(fileName)) {
            return extractWordText(fileBytes, fileName);
        } else if (isText(fileName)) {
            return new String(fileBytes, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("不支持的文件类型：" + fileName
                    + "（仅支持 PDF/Word/TXT）");
        }
    }

    /** PDF → 纯文本 */
    private String extractPdfText(byte[] fileBytes) throws Exception {
        try (PDDocument document = PDDocument.load(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /** Word → 纯文本（.docx 用 XWPFDocument；老 .doc 直接按文本尝试） */
    private String extractWordText(byte[] fileBytes, String fileName) throws Exception {
        if (fileName.toLowerCase().endsWith(".doc")) {
            // 老 .doc 格式（二进制）需要 HWPF，简单起见当做文本尝试
            return new String(fileBytes, StandardCharsets.UTF_8);
        }
        // .docx（主流）
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    // ================== 生成文档 ==================

    /**
     * 生成 PDF 文件（自动过滤 emoji 等特殊字符，避免字体渲染失败）
     * @param title 文档标题
     * @param content 文档正文（纯文本，自动按换行分段）
     * @return PDF 字节数组
     */
    public byte[] createPdf(String title, String content) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);

            // 自动查找系统中文字体
            org.apache.pdfbox.pdmodel.font.PDFont font = loadChineseFont(document);
            float fontSizeTitle = 18f;
            float fontSizeBody = 12f;
            float leading = 22f;

            // 清理 emoji 和特殊字符（字体不支持会直接崩溃）
            String cleanTitle = sanitizeForDocument(title);
            String cleanContent = sanitizeForDocument(content);

            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {

                float pageHeight = page.getMediaBox().getHeight();
                float margin = 50;
                float yPosition = pageHeight - margin;

                // 标题
                contentStream.setFont(font, fontSizeTitle);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText(cleanTitle);
                contentStream.endText();
                yPosition -= leading * 2;

                // 正文（按行遍历，每行再按字符数截断避免超出页面）
                contentStream.setFont(font, fontSizeBody);
                if (cleanContent != null && !cleanContent.trim().isEmpty()) {
                    String[] lines = cleanContent.split("\n");
                    int maxCharsPerLine = 45;
                    boolean pageFull = false;

                    for (String line : lines) {
                        if (pageFull) break;
                        if (line == null || line.trim().isEmpty()) {
                            yPosition -= leading;
                            continue;
                        }

                        String text = line.trim();
                        for (int i = 0; i < text.length(); i += maxCharsPerLine) {
                            int end = Math.min(i + maxCharsPerLine, text.length());
                            String segment = text.substring(i, end);
                            contentStream.beginText();
                            contentStream.newLineAtOffset(margin, yPosition);
                            contentStream.showText(segment);
                            contentStream.endText();
                            yPosition -= leading;
                            if (yPosition < margin) {
                                pageFull = true;
                                break;
                            }
                        }
                        yPosition -= leading / 2;
                    }
                }
            }

            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 过滤 emoji 和特殊符号（系统中文字体不支持，会导致 PDF 渲染失败）。
     * 保留：中文、英文字母、数字、常见标点和空白字符。
     */
    private String sanitizeForDocument(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 普通 ASCII（字母、数字、标点、空格、换行）
            if (c < 0x80) {
                // 过滤掉 ASCII 控制字符（除了 \n \r \t）
                if (c == '\n' || c == '\r' || c == '\t' || c >= 32) {
                    sb.append(c);
                }
                continue;
            }

            // 中文汉字（CJK 统一表意文字）
            if (c >= 0x4E00 && c <= 0x9FFF) { sb.append(c); continue; }
            // 中文标点（CJK 符号和标点）
            if (c >= 0x3000 && c <= 0x303F) { sb.append(c); continue; }
            // 全角 ASCII
            if (c >= 0xFF00 && c <= 0xFFEF) { sb.append(c); continue; }

            // 其他（emoji、特殊符号等）—— 一律丢弃
            continue;
        }
        return sb.toString().trim();
    }

    /** 尝试加载系统中文字体，找到第一个可用字体就返回（不打印日志） */
    private org.apache.pdfbox.pdmodel.font.PDFont loadChineseFont(PDDocument document) {
        String[] fontPaths = {
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simhei.ttf",
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        };

        for (String path : fontPaths) {
            try {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return org.apache.pdfbox.pdmodel.font.PDType0Font.load(document, fontFile);
                }
            } catch (Exception ignored) {
                // 这个字体加载失败，尝试下一个
            }
        }
        return org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
    }

    /**
     * 生成 Word 文件 (.docx)
     * @param title 文档标题
     * @param content 文档正文（纯文本，自动按换行分段）
     * @return Word 字节数组
     */
    public byte[] createWord(String title, String content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 同样清理 emoji（Word 虽然支持更多字符，但保持一致更保险）
            String cleanTitle = sanitizeForDocument(title);
            String cleanContent = sanitizeForDocument(content);

            // 标题段
            XWPFParagraph titlePara = doc.createParagraph();
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(cleanTitle);
            titleRun.setBold(true);
            titleRun.setFontSize(20);
            titleRun.addBreak();

            // 正文按换行分段
            if (cleanContent != null && !cleanContent.trim().isEmpty()) {
                for (String line : cleanContent.split("\n")) {
                    if (line == null || line.trim().isEmpty()) {
                        doc.createParagraph();
                        continue;
                    }
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText(line.trim());
                    r.setFontSize(12);
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }
}