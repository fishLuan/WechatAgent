package com.clawbot.wechatbot.service.document;

import com.clawbot.wechatbot.service.support.DocumentTextSanitizer;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Word 文本提取和 DOCX 生成。 */
public class WordDocumentService {
    public String extractText(byte[] fileBytes, String fileName) throws Exception {
        if (fileName.toLowerCase().endsWith(".doc")) {
            try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(fileBytes));
                 WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        }
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    public byte[] create(String title, String content) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun titleRun = document.createParagraph().createRun();
            titleRun.setText(DocumentTextSanitizer.sanitize(title));
            titleRun.setBold(true);
            titleRun.setFontSize(20);
            titleRun.addBreak();

            String cleanContent = DocumentTextSanitizer.sanitize(content);
            for (String line : cleanContent.split("\\n")) {
                XWPFParagraph paragraph = document.createParagraph();
                if (!line.isBlank()) {
                    XWPFRun run = paragraph.createRun();
                    run.setText(line.trim());
                    run.setFontSize(12);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }
}
