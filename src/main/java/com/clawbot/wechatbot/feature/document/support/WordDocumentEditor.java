package com.clawbot.wechatbot.feature.document.support;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** DOCX 段落级编辑器，第一版优先保证文本编辑稳定。 */
@Component
public class WordDocumentEditor {
    public String extractText(byte[] bytes) throws Exception {
        try (XWPFDocument document = open(bytes);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText().trim();
        }
    }

    public byte[] replace(byte[] bytes, String oldText, String newText) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : editableParagraphs(document)) {
                replaceInParagraph(paragraph, oldText, newText == null ? "" : newText);
            }
            return write(document);
        }
    }

    public byte[] append(byte[] bytes, String text) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            return write(document);
        }
    }

    public byte[] prepend(byte[] bytes, String text) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            XWPFParagraph first = document.getParagraphs().isEmpty()
                ? document.createParagraph()
                : document.getParagraphs().get(0);
            XmlCursor cursor = first.getCTP().newCursor();
            XWPFParagraph paragraph = document.insertNewParagraph(cursor);
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            return write(document);
        }
    }

    public byte[] deleteText(byte[] bytes, String textToDelete) throws Exception {
        return replace(bytes, textToDelete, "");
    }

    public byte[] clearContent(byte[] bytes) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (int i = document.getBodyElements().size() - 1; i >= 0; i--) {
                document.removeBodyElement(i);
            }
            document.createParagraph();
            return write(document);
        }
    }

    public byte[] format(byte[] bytes, boolean largerFont, boolean center) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                if (center) paragraph.setAlignment(ParagraphAlignment.CENTER);
                if (largerFont) {
                    for (XWPFRun run : paragraph.getRuns()) {
                        run.setFontSize(resolveLargerFontSize(run.getFontSize()));
                    }
                }
            }
            return write(document);
        }
    }

    public byte[] setFontSize(byte[] bytes, int fontSize) throws Exception {
        return updateRuns(bytes, run -> run.setFontSize(fontSize));
    }

    public byte[] setTitleFontSize(byte[] bytes, int fontSize) throws Exception {
        return updateRuns(bytes, Scope.TITLE, run -> run.setFontSize(fontSize));
    }

    public byte[] setBodyFontSize(byte[] bytes, int fontSize) throws Exception {
        return updateRuns(bytes, Scope.BODY, run -> run.setFontSize(fontSize));
    }

    public byte[] setFontFamily(byte[] bytes, String fontFamily) throws Exception {
        return updateRuns(bytes, run -> run.setFontFamily(fontFamily));
    }

    public byte[] setTitleFontFamily(byte[] bytes, String fontFamily) throws Exception {
        return updateRuns(bytes, Scope.TITLE, run -> run.setFontFamily(fontFamily));
    }

    public byte[] setBodyFontFamily(byte[] bytes, String fontFamily) throws Exception {
        return updateRuns(bytes, Scope.BODY, run -> run.setFontFamily(fontFamily));
    }

    public byte[] setBold(byte[] bytes, boolean bold) throws Exception {
        return updateRuns(bytes, run -> run.setBold(bold));
    }

    public byte[] setTitleBold(byte[] bytes, boolean bold) throws Exception {
        return updateRuns(bytes, Scope.TITLE, run -> run.setBold(bold));
    }

    public byte[] setBodyBold(byte[] bytes, boolean bold) throws Exception {
        return updateRuns(bytes, Scope.BODY, run -> run.setBold(bold));
    }

    public byte[] smallerFont(byte[] bytes) throws Exception {
        return updateRuns(bytes, run -> run.setFontSize(resolveSmallerFontSize(run.getFontSize())));
    }

    public byte[] align(byte[] bytes, ParagraphAlignment alignment) throws Exception {
        return align(bytes, Scope.ALL, alignment);
    }

    public byte[] alignTitle(byte[] bytes, ParagraphAlignment alignment) throws Exception {
        return align(bytes, Scope.TITLE, alignment);
    }

    public byte[] alignBody(byte[] bytes, ParagraphAlignment alignment) throws Exception {
        return align(bytes, Scope.BODY, alignment);
    }

    private byte[] align(byte[] bytes, Scope scope, ParagraphAlignment alignment) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : scopedParagraphs(document, scope)) {
                paragraph.setAlignment(alignment);
            }
            return write(document);
        }
    }

    public byte[] setFirstLineIndent(byte[] bytes, boolean enabled) throws Exception {
        return setFirstLineIndent(bytes, Scope.ALL, enabled);
    }

    public byte[] setBodyFirstLineIndent(byte[] bytes, boolean enabled) throws Exception {
        return setFirstLineIndent(bytes, Scope.BODY, enabled);
    }

    private byte[] setFirstLineIndent(byte[] bytes, Scope scope, boolean enabled) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : scopedParagraphs(document, scope)) {
                paragraph.setIndentationFirstLine(enabled ? 480 : 0);
            }
            return write(document);
        }
    }

    public byte[] setLineSpacing(byte[] bytes, double spacing) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                paragraph.setSpacingBetween(spacing);
            }
            return write(document);
        }
    }

    public byte[] enlargeParagraphSpacing(byte[] bytes) throws Exception {
        return setSpacingAfter(bytes, 12);
    }

    public byte[] setSpacingBefore(byte[] bytes, int points) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                paragraph.setSpacingBefore(points * 20);
            }
            return write(document);
        }
    }

    public byte[] setSpacingAfter(byte[] bytes, int points) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                paragraph.setSpacingAfter(points * 20);
            }
            return write(document);
        }
    }

    public byte[] deleteEmptyLines(byte[] bytes) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (int i = document.getParagraphs().size() - 1; i >= 0; i--) {
                XWPFParagraph paragraph = document.getParagraphs().get(i);
                if (paragraph.getText() == null || paragraph.getText().isBlank()) {
                    int position = document.getPosOfParagraph(paragraph);
                    if (position >= 0) document.removeBodyElement(position);
                }
            }
            if (document.getParagraphs().isEmpty()) document.createParagraph();
            return write(document);
        }
    }

    public byte[] setTitle(byte[] bytes, String title) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            XWPFParagraph paragraph = document.getParagraphs().isEmpty()
                ? document.createParagraph()
                : document.getParagraphs().get(0);
            rewriteParagraph(paragraph, title);
            applyTitleStyle(document, paragraph);
            return write(document);
        }
    }

    public byte[] firstLineAsTitle(byte[] bytes) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            if (!document.getParagraphs().isEmpty()) {
                applyTitleStyle(document, document.getParagraphs().get(0));
            }
            return write(document);
        }
    }

    public byte[] beautify(byte[] bytes) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            if (!document.getParagraphs().isEmpty()) {
                applyTitleStyle(document, document.getParagraphs().get(0));
            }
            for (int i = 1; i < document.getParagraphs().size(); i++) {
                XWPFParagraph paragraph = document.getParagraphs().get(i);
                paragraph.setAlignment(ParagraphAlignment.BOTH);
                paragraph.setIndentationFirstLine(480);
                paragraph.setSpacingBetween(1.5);
                paragraph.setSpacingAfter(120);
                for (XWPFRun run : paragraph.getRuns()) {
                    if (run.getFontSize() <= 0) run.setFontSize(12);
                    if (run.getFontFamily() == null) run.setFontFamily("宋体");
                }
            }
            return write(document);
        }
    }

    public byte[] addHeading1(byte[] bytes, String text) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            ensureParagraphStyle(document, "Heading1", "heading 1");
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setStyle("Heading1");
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            paragraph.setAlignment(ParagraphAlignment.LEFT);
            run.setBold(true);
            run.setFontFamily("黑体");
            run.setFontSize(18);
            paragraph.setSpacingBefore(240);
            paragraph.setSpacingAfter(120);
            return write(document);
        }
    }

    public byte[] addHeading2(byte[] bytes, String text) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            ensureParagraphStyle(document, "Heading2", "heading 2");
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setStyle("Heading2");
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            paragraph.setAlignment(ParagraphAlignment.LEFT);
            run.setBold(true);
            run.setFontFamily("黑体");
            run.setFontSize(15);
            paragraph.setSpacingBefore(160);
            paragraph.setSpacingAfter(80);
            return write(document);
        }
    }

    public byte[] addParagraph(byte[] bytes, String text) throws Exception {
        return append(bytes, text);
    }

    public byte[] addPageBreak(byte[] bytes) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().addBreak(BreakType.PAGE);
            return write(document);
        }
    }

    public byte[] deleteParagraphContaining(byte[] bytes, String keyword) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (int i = document.getParagraphs().size() - 1; i >= 0; i--) {
                XWPFParagraph paragraph = document.getParagraphs().get(i);
                String text = paragraph.getText();
                if (text != null && text.contains(keyword)) {
                    int position = document.getPosOfParagraph(paragraph);
                    if (position >= 0) document.removeBodyElement(position);
                }
            }
            if (document.getParagraphs().isEmpty()) document.createParagraph();
            return write(document);
        }
    }

    public byte[] setParagraphFontSize(byte[] bytes, int index, int size) throws Exception {
        return updateSelectedRuns(bytes, index, null, run -> run.setFontSize(size));
    }

    public byte[] setParagraphFontFamily(byte[] bytes, int index, String family) throws Exception {
        return updateSelectedRuns(bytes, index, null, run -> run.setFontFamily(family));
    }

    public byte[] setParagraphBold(byte[] bytes, int index, boolean bold) throws Exception {
        return updateSelectedRuns(bytes, index, null, run -> run.setBold(bold));
    }

    public byte[] alignParagraph(
        byte[] bytes, int index, ParagraphAlignment alignment
    ) throws Exception {
        return alignSelected(bytes, index, null, alignment);
    }

    public byte[] setMatchingParagraphFontSize(
        byte[] bytes, String keyword, int size
    ) throws Exception {
        return updateSelectedRuns(bytes, null, keyword, run -> run.setFontSize(size));
    }

    public byte[] setMatchingParagraphFontFamily(
        byte[] bytes, String keyword, String family
    ) throws Exception {
        return updateSelectedRuns(bytes, null, keyword, run -> run.setFontFamily(family));
    }

    public byte[] setMatchingParagraphBold(
        byte[] bytes, String keyword, boolean bold
    ) throws Exception {
        return updateSelectedRuns(bytes, null, keyword, run -> run.setBold(bold));
    }

    public byte[] alignMatchingParagraph(
        byte[] bytes, String keyword, ParagraphAlignment alignment
    ) throws Exception {
        return alignSelected(bytes, null, keyword, alignment);
    }

    private XWPFDocument open(byte[] bytes) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private byte[] write(XWPFDocument document) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            return out.toByteArray();
        }
    }

    private void rewriteParagraph(XWPFParagraph paragraph, String text) {
        RunStyle style = captureFirstRunStyle(paragraph);
        int runs = paragraph.getRuns().size();
        for (int i = runs - 1; i >= 0; i--) {
            paragraph.removeRun(i);
        }
        XWPFRun run = paragraph.createRun();
        style.applyTo(run);
        run.setText(text == null ? "" : text);
    }

    private void replaceInParagraph(XWPFParagraph paragraph, String oldText, String newText) {
        if (oldText == null || oldText.isEmpty() || paragraph.getRuns().isEmpty()) return;
        String source = paragraph.getRuns().stream()
            .map(XWPFRun::text)
            .reduce("", String::concat);
        List<Integer> matches = new ArrayList<>();
        for (int at = source.indexOf(oldText); at >= 0;
             at = source.indexOf(oldText, at + oldText.length())) {
            matches.add(at);
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            replaceRunRange(paragraph, matches.get(i), oldText.length(), newText);
        }
    }

    private void replaceRunRange(
        XWPFParagraph paragraph, int start, int length, String replacement
    ) {
        List<XWPFRun> runs = paragraph.getRuns();
        int end = start + length;
        int cursor = 0;
        int startRun = -1;
        int endRun = -1;
        int startOffset = 0;
        int endOffset = 0;
        for (int i = 0; i < runs.size(); i++) {
            String text = runs.get(i).text();
            int next = cursor + text.length();
            if (startRun < 0 && start >= cursor && start < next) {
                startRun = i;
                startOffset = start - cursor;
            }
            if (endRun < 0 && end > cursor && end <= next) {
                endRun = i;
                endOffset = end - cursor;
                break;
            }
            cursor = next;
        }
        if (startRun < 0 || endRun < 0) return;
        String first = runs.get(startRun).text();
        if (startRun == endRun) {
            setRunText(runs.get(startRun), first.substring(0, startOffset)
                + replacement + first.substring(endOffset));
            return;
        }
        setRunText(runs.get(startRun), first.substring(0, startOffset) + replacement);
        for (int i = startRun + 1; i < endRun; i++) setRunText(runs.get(i), "");
        String last = runs.get(endRun).text();
        setRunText(runs.get(endRun), last.substring(endOffset));
    }

    private void setRunText(XWPFRun run, String text) {
        while (run.getCTR().sizeOfTArray() > 0) run.getCTR().removeT(0);
        run.setText(text == null ? "" : text);
    }

    private RunStyle captureFirstRunStyle(XWPFParagraph paragraph) {
        if (paragraph.getRuns().isEmpty()) {
            return new RunStyle(null, -1, null);
        }
        XWPFRun run = paragraph.getRuns().get(0);
        return new RunStyle(run.getFontFamily(), run.getFontSize(), run.isBold());
    }

    private byte[] updateRuns(byte[] bytes, RunUpdater updater) throws Exception {
        return updateRuns(bytes, Scope.ALL, updater);
    }

    private byte[] updateRuns(byte[] bytes, Scope scope, RunUpdater updater) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : scopedParagraphs(document, scope)) {
                ensureRun(paragraph);
                for (XWPFRun run : paragraph.getRuns()) {
                    updater.update(run);
                }
            }
            return write(document);
        }
    }

    private byte[] updateSelectedRuns(
        byte[] bytes, Integer index, String keyword, RunUpdater updater
    ) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : selectedParagraphs(document, index, keyword)) {
                ensureRun(paragraph);
                for (XWPFRun run : paragraph.getRuns()) updater.update(run);
            }
            return write(document);
        }
    }

    private byte[] alignSelected(
        byte[] bytes, Integer index, String keyword, ParagraphAlignment alignment
    ) throws Exception {
        try (XWPFDocument document = open(bytes)) {
            for (XWPFParagraph paragraph : selectedParagraphs(document, index, keyword)) {
                paragraph.setAlignment(alignment);
            }
            return write(document);
        }
    }

    private java.util.List<XWPFParagraph> selectedParagraphs(
        XWPFDocument document, Integer index, String keyword
    ) {
        java.util.List<XWPFParagraph> nonEmpty = contentParagraphs(document).stream()
            .filter(paragraph -> paragraph.getText() != null
                && !paragraph.getText().isBlank())
            .toList();
        if (index != null) {
            return index > 0 && index <= nonEmpty.size()
                ? java.util.List.of(nonEmpty.get(index - 1)) : java.util.List.of();
        }
        return nonEmpty.stream()
            .filter(paragraph -> paragraph.getText().contains(keyword))
            .toList();
    }

    private java.util.List<XWPFParagraph> scopedParagraphs(XWPFDocument document, Scope scope) {
        java.util.List<XWPFParagraph> paragraphs = scope == Scope.ALL
            ? editableParagraphs(document) : contentParagraphs(document);
        if (scope == Scope.ALL || paragraphs.isEmpty()) return paragraphs;
        if (scope == Scope.TITLE) return paragraphs.subList(0, 1);
        return paragraphs.size() <= 1
            ? java.util.List.of()
            : paragraphs.subList(1, paragraphs.size());
    }

    private void ensureRun(XWPFParagraph paragraph) {
        if (paragraph.getRuns().isEmpty()) {
            paragraph.createRun();
        }
    }

    private void applyTitleStyle(XWPFDocument document, XWPFParagraph paragraph) {
        ensureParagraphStyle(document, "Title", "Title");
        paragraph.setStyle("Title");
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        ensureRun(paragraph);
        for (XWPFRun run : paragraph.getRuns()) {
            run.setBold(true);
            run.setFontSize(20);
            if (run.getFontFamily() == null) run.setFontFamily("黑体");
        }
        paragraph.setSpacingAfter(240);
    }

    private List<XWPFParagraph> editableParagraphs(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = contentParagraphs(document);
        for (XWPFHeader header : document.getHeaderList()) {
            addBodyParagraphs(header.getBodyElements(), paragraphs);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            addBodyParagraphs(footer.getBodyElements(), paragraphs);
        }
        return paragraphs;
    }

    private List<XWPFParagraph> contentParagraphs(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = new ArrayList<>();
        addBodyParagraphs(document.getBodyElements(), paragraphs);
        return paragraphs;
    }

    private void addBodyParagraphs(
        List<IBodyElement> elements, List<XWPFParagraph> paragraphs
    ) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                paragraphs.add(paragraph);
            } else if (element instanceof XWPFTable table) {
                table.getRows().forEach(row -> row.getTableCells().forEach(cell ->
                    addBodyParagraphs(cell.getBodyElements(), paragraphs)));
            }
        }
    }

    private void ensureParagraphStyle(XWPFDocument document, String styleId, String name) {
        XWPFStyles styles = document.getStyles();
        if (styles == null) styles = document.createStyles();
        if (styles.styleExist(styleId)) return;
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId(styleId);
        style.setType(STStyleType.PARAGRAPH);
        style.addNewName().setVal(name);
        styles.addStyle(new XWPFStyle(style));
    }

    private int resolveLargerFontSize(int current) {
        if (current <= 0) return 16;
        return Math.max(current + 4, 16);
    }

    private int resolveSmallerFontSize(int current) {
        if (current <= 0) return 10;
        return Math.max(current - 2, 8);
    }

    private interface RunUpdater {
        void update(XWPFRun run);
    }

    private enum Scope {
        ALL,
        TITLE,
        BODY
    }

    private record RunStyle(String fontFamily, int fontSize, Boolean bold) {
        void applyTo(XWPFRun run) {
            if (fontFamily != null) run.setFontFamily(fontFamily);
            if (fontSize > 0) run.setFontSize(fontSize);
            if (bold != null) run.setBold(bold);
        }
    }
}
