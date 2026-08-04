package com.clawbot.wechatbot.service.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentFormatServiceTests {

    @Test
    void generatedPdfContinuesOnAdditionalPagesWithoutDroppingTail() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 180; i++) {
            content.append("Line ").append(i).append(" generated document content\n");
        }
        content.append("FINAL-END-MARKER");

        byte[] bytes = new PdfDocumentService().create("Report", content.toString());

        try (PDDocument document = PDDocument.load(bytes)) {
            assertTrue(document.getNumberOfPages() > 1);
            assertTrue(new PDFTextStripper().getText(document).contains("FINAL-END-MARKER"));
        }
    }

    @Test
    void wordExtractionIncludesTableCells() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文内容");
            document.createTable(1, 1).getRow(0).getCell(0).setText("表格内容");
            document.write(out);
            bytes = out.toByteArray();
        }

        String extracted = new WordDocumentService().extractText(bytes, "report.docx");
        assertTrue(extracted.contains("正文内容"));
        assertTrue(extracted.contains("表格内容"));
    }
}
