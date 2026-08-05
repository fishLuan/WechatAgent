package com.clawbot.wechatbot.feature.document.support;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordDocumentEditorTests {

    @Test
    void editsDocxParagraphText() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("甲方：张三\n临时内容");

        bytes = editor.replace(bytes, "张三", "李四");
        bytes = editor.append(bytes, "以上内容仅供参考。");
        bytes = editor.deleteText(bytes, "临时内容");

        String text = editor.extractText(bytes);
        assertTrue(text.contains("甲方：李四"));
        assertTrue(text.contains("以上内容仅供参考。"));
        assertFalse(text.contains("临时内容"));
    }

    @Test
    void formatsParagraphsWithLargerCenteredText() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = editor.format(docx("你好"), true, true);

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            assertTrue(document.getParagraphs().stream()
                .allMatch(paragraph -> paragraph.getAlignment() == ParagraphAlignment.CENTER));
            assertTrue(document.getParagraphs().stream()
                .flatMap(paragraph -> paragraph.getRuns().stream())
                .allMatch(run -> run.getFontSize() >= 16));
        }
    }

    @Test
    void appliesCommonFormattingOperations() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("标题\n正文内容");

        bytes = editor.setFontSize(bytes, 18);
        bytes = editor.setFontFamily(bytes, "微软雅黑");
        bytes = editor.setBold(bytes, true);
        bytes = editor.align(bytes, ParagraphAlignment.BOTH);
        bytes = editor.setFirstLineIndent(bytes, true);
        bytes = editor.setLineSpacing(bytes, 1.5);
        bytes = editor.firstLineAsTitle(bytes);

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(ParagraphAlignment.CENTER, document.getParagraphs().get(0).getAlignment());
            assertTrue(document.getParagraphs().get(0).getRuns().get(0).isBold());
            assertTrue(document.getParagraphs().get(0).getRuns().get(0).getFontSize() >= 18);
            assertEquals(480, document.getParagraphs().get(1).getIndentationFirstLine());
        }
    }

    @Test
    void prependsTitleAndDeletesEmptyLines() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("正文\n\n结尾");

        bytes = editor.prepend(bytes, "新增标题");
        bytes = editor.deleteEmptyLines(bytes);

        String text = editor.extractText(bytes);
        assertTrue(text.startsWith("新增标题"));
        assertFalse(text.contains("\n\n"));
    }

    @Test
    void replacePreservesRunFormatting() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("旧公司名计划");

        bytes = editor.setFontSize(bytes, 18);
        bytes = editor.setFontFamily(bytes, "微软雅黑");
        bytes = editor.setBold(bytes, true);
        bytes = editor.replace(bytes, "旧公司名", "ClawBot团队");

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            var run = document.getParagraphs().get(0).getRuns().get(0);
            assertEquals("ClawBot团队计划", document.getParagraphs().get(0).getText());
            assertEquals(18, run.getFontSize());
            assertEquals("微软雅黑", run.getFontFamily());
            assertTrue(run.isBold());
        }
    }

    @Test
    void replacesAcrossRunsWithoutFlatteningUnrelatedFormatting() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var paragraph = document.createParagraph();
            var first = paragraph.createRun();
            first.setText("旧");
            first.setBold(true);
            var second = paragraph.createRun();
            second.setText("公司名");
            second.setItalic(true);
            var suffix = paragraph.createRun();
            suffix.setText("计划");
            suffix.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE);
            document.write(out);
            bytes = out.toByteArray();
        }

        bytes = editor.replace(bytes, "旧公司名", "新名称");

        try (XWPFDocument document = new XWPFDocument(
            new java.io.ByteArrayInputStream(bytes))) {
            var paragraph = document.getParagraphs().get(0);
            assertEquals("新名称计划", paragraph.getText());
            assertEquals(3, paragraph.getRuns().size());
            assertTrue(paragraph.getRuns().get(0).isBold());
            assertTrue(paragraph.getRuns().get(1).isItalic());
            assertNotEquals(org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE,
                paragraph.getRuns().get(2).getUnderline());
        }
    }

    @Test
    void extractsAndReplacesTextInsideTables() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文");
            document.createTable(1, 1).getRow(0).getCell(0)
                .setText("表格中的旧公司名");
            document.write(out);
            bytes = out.toByteArray();
        }

        assertTrue(editor.extractText(bytes).contains("表格中的旧公司名"));
        bytes = editor.replace(bytes, "旧公司名", "新名称");
        assertTrue(editor.extractText(bytes).contains("表格中的新名称"));
    }

    @Test
    void appliesTitleAndBodyScopedFormatting() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("标题\n正文一\n正文二");

        bytes = editor.setTitleFontSize(bytes, 22);
        bytes = editor.setTitleFontFamily(bytes, "黑体");
        bytes = editor.alignTitle(bytes, ParagraphAlignment.CENTER);
        bytes = editor.setBodyFontSize(bytes, 12);
        bytes = editor.setBodyFontFamily(bytes, "宋体");
        bytes = editor.alignBody(bytes, ParagraphAlignment.BOTH);
        bytes = editor.setBodyFirstLineIndent(bytes, true);

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            var title = document.getParagraphs().get(0);
            var body = document.getParagraphs().get(1);
            assertEquals(ParagraphAlignment.CENTER, title.getAlignment());
            assertEquals(22, title.getRuns().get(0).getFontSize());
            assertEquals("黑体", title.getRuns().get(0).getFontFamily());
            assertEquals(ParagraphAlignment.BOTH, body.getAlignment());
            assertEquals(12, body.getRuns().get(0).getFontSize());
            assertEquals("宋体", body.getRuns().get(0).getFontFamily());
            assertEquals(480, body.getIndentationFirstLine());
        }
    }

    @Test
    void addsStructureAndDeletesParagraphContainingKeyword() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("标题\n正文\n删除我这一段");

        bytes = editor.addHeading1(bytes, "新增一级标题");
        bytes = editor.addHeading2(bytes, "新增二级标题");
        bytes = editor.addParagraph(bytes, "新增段落");
        bytes = editor.deleteParagraphContaining(bytes, "删除我");

        String text = editor.extractText(bytes);
        assertTrue(text.contains("新增一级标题"));
        assertTrue(text.contains("新增二级标题"));
        assertTrue(text.contains("新增段落"));
        assertFalse(text.contains("删除我这一段"));

        try (XWPFDocument document = new XWPFDocument(
            new java.io.ByteArrayInputStream(bytes))) {
            assertEquals("Heading1", document.getParagraphs().get(2).getStyle());
            assertEquals("Heading2", document.getParagraphs().get(3).getStyle());
        }
    }

    @Test
    void formatsParagraphByIndexAndKeyword() throws Exception {
        WordDocumentEditor editor = new WordDocumentEditor();
        byte[] bytes = docx("标题\n第二段内容\n风险说明内容");

        bytes = editor.setParagraphBold(bytes, 2, true);
        bytes = editor.setParagraphFontSize(bytes, 2, 16);
        bytes = editor.alignMatchingParagraph(
            bytes, "风险说明", ParagraphAlignment.CENTER);

        try (XWPFDocument document = new XWPFDocument(
            new java.io.ByteArrayInputStream(bytes))) {
            assertTrue(document.getParagraphs().get(1).getRuns().get(0).isBold());
            assertEquals(16, document.getParagraphs().get(1).getRuns().get(0).getFontSize());
            assertEquals(ParagraphAlignment.CENTER,
                document.getParagraphs().get(2).getAlignment());
        }
    }

    private byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : text.split("\\n")) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(out);
            return out.toByteArray();
        }
    }
}
