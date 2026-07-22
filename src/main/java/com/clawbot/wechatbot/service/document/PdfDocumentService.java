package com.clawbot.wechatbot.service.document;

import com.clawbot.wechatbot.service.support.DocumentTextSanitizer;
import com.clawbot.wechatbot.service.support.PdfLogSilencer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.File;

/** PDF 文本提取和生成。 */
public class PdfDocumentService {
    private static final String[] FONT_PATHS = {
        "C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf",
        "/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/STHeiti Light.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
    };

    static { PdfLogSilencer.silence(); }

    public String extractText(byte[] fileBytes) throws Exception {
        try (PDDocument document = PDDocument.load(fileBytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    public byte[] create(String title, String content) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDFont font = loadChineseFont(document);
            String cleanTitle = DocumentTextSanitizer.sanitize(title);
            String cleanContent = DocumentTextSanitizer.sanitize(content);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                float margin = 50;
                float leading = 22;
                float y = page.getMediaBox().getHeight() - margin;
                stream.setFont(font, 18);
                writeLine(stream, cleanTitle, margin, y);
                y -= leading * 2;
                stream.setFont(font, 12);

                for (String line : cleanContent.split("\\n")) {
                    if (y < margin) break;
                    if (line.isBlank()) { y -= leading; continue; }
                    String text = line.trim();
                    for (int i = 0; i < text.length() && y >= margin; i += 45) {
                        writeLine(stream, text.substring(i, Math.min(i + 45, text.length())), margin, y);
                        y -= leading;
                    }
                    y -= leading / 2;
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private void writeLine(PDPageContentStream stream, String text, float x, float y) throws Exception {
        stream.beginText();
        stream.newLineAtOffset(x, y);
        stream.showText(text == null ? "" : text);
        stream.endText();
    }

    private PDFont loadChineseFont(PDDocument document) {
        for (String path : FONT_PATHS) {
            try {
                File file = new File(path);
                if (file.exists()) return PDType0Font.load(document, file);
            } catch (Exception ignored) {}
        }
        return PDType1Font.HELVETICA;
    }
}
