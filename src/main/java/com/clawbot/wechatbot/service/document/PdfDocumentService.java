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
import java.util.ArrayList;
import java.util.List;

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
            PDFont font = loadChineseFont(document);
            String cleanTitle = DocumentTextSanitizer.sanitize(title);
            String cleanContent = DocumentTextSanitizer.sanitize(content);
            writePages(document, font, cleanTitle, cleanContent);
            document.save(out);
            return out.toByteArray();
        }
    }

    private void writePages(
        PDDocument document, PDFont font, String title, String content
    ) throws Exception {
        float margin = 50;
        float bodySize = 12;
        float leading = 18;
        PDPage page = new PDPage();
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        try {
            float y = page.getMediaBox().getHeight() - margin;
            stream.setFont(font, 18);
            writeLine(stream, title, margin, y);
            y -= leading * 2;
            stream.setFont(font, bodySize);
            float width = page.getMediaBox().getWidth() - margin * 2;

            for (String sourceLine : content.split("\\R", -1)) {
                List<String> lines = sourceLine.isBlank()
                    ? List.of("") : wrap(sourceLine, font, bodySize, width);
                for (String line : lines) {
                    if (y < margin + leading) {
                        stream.close();
                        page = new PDPage();
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        stream.setFont(font, bodySize);
                        y = page.getMediaBox().getHeight() - margin;
                    }
                    if (!line.isEmpty()) writeLine(stream, line, margin, y);
                    y -= leading;
                }
                y -= leading / 2;
            }
        } finally {
            stream.close();
        }
    }

    private List<String> wrap(String text, PDFont font, float fontSize, float maxWidth)
        throws Exception {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            String candidate = line + next;
            if (!line.isEmpty() && textWidth(candidate, font, fontSize) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(next);
            offset += Character.charCount(codePoint);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private float textWidth(String text, PDFont font, float fontSize) throws Exception {
        return font.getStringWidth(text) / 1000f * fontSize;
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
