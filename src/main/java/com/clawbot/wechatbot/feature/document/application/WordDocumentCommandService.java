package com.clawbot.wechatbot.feature.document.application;

import com.clawbot.wechatbot.feature.document.messaging.WordDocumentCommandParser;
import com.clawbot.wechatbot.feature.document.model.WordDocumentEditResult;
import com.clawbot.wechatbot.feature.document.model.WordDocumentSession;
import com.clawbot.wechatbot.feature.document.repository.WordDocumentSessionRepository;
import com.clawbot.wechatbot.feature.document.support.WordDocumentEditor;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class WordDocumentCommandService {
    private static final int PREVIEW_LIMIT = 1200;
    private static final int BATCH_REPORT_LIMIT = 8;
    private static final int BATCH_UNSUPPORTED_LIMIT = 3;

    private final WordDocumentSessionRepository sessions;
    private final WordDocumentEditor editor;
    private final WordDocumentNaturalLanguagePlanner naturalLanguagePlanner;

    public WordDocumentCommandService(
        WordDocumentSessionRepository sessions,
        WordDocumentEditor editor,
        WordDocumentNaturalLanguagePlanner naturalLanguagePlanner
    ) {
        this.sessions = sessions;
        this.editor = editor;
        this.naturalLanguagePlanner = naturalLanguagePlanner;
    }

    public WordDocumentEditResult createSession(
        String userId, String fileName, byte[] bytes
    ) {
        try {
            if (!isDocx(fileName)) {
                return WordDocumentEditResult.failure("目前只支持 .docx Word 文档。");
            }
            sessions.findByWechatUserIdAndActiveTrueOrderByUpdatedAtDesc(userId)
                .forEach(session -> {
                    session.setActive(false);
                    session.setUpdatedAt(Instant.now());
                    sessions.save(session);
                });
            WordDocumentSession session = new WordDocumentSession(userId, fileName, bytes);
            session.setExtractedText(editor.extractText(bytes));
            session.setUpdatedAt(Instant.now());
            return WordDocumentEditResult.success("已创建 Word 编辑会话。", sessions.save(session));
        } catch (Exception e) {
            return WordDocumentEditResult.failure("Word 文档读取失败：" + e.getMessage());
        }
    }

    public WordDocumentEditResult handle(String userId, String input) {
        List<WordDocumentCommandParser.ParsedCommand> batchCommands =
            WordDocumentCommandParser.parseMany(input);
        WordDocumentCommandParser.ParsedCommand command =
            batchCommands.size() == 1 ? batchCommands.get(0) : WordDocumentCommandParser.parse(input);
        if (command.type() == WordDocumentCommandParser.CommandType.HELP) {
            return WordDocumentEditResult.success(helpText(), null);
        }
        WordDocumentSession session = currentSession(userId);
        if (session == null) {
            return WordDocumentEditResult.failure("当前没有可编辑的 Word 文档，请先发送 .docx 文件。");
        }
        try {
            List<String> unsupported = WordDocumentCommandParser.unsupportedLines(input);
            if (batchCommands.isEmpty() || !unsupported.isEmpty()) {
                WordDocumentEditResult planned = handleNaturalLanguage(session, input);
                if (planned != null) return planned;
            }
            if (batchCommands.size() > 1) return handleBatch(session, input, batchCommands);
            return execute(session, command);
        } catch (Exception e) {
            return WordDocumentEditResult.failure("Word 文档操作失败：" + e.getMessage());
        }
    }

    public WordDocumentEditResult applyInstruction(String userId, String input) {
        return handle(userId, input);
    }

    public boolean hasActiveSession(String userId) {
        return currentSession(userId) != null;
    }

    public WordDocumentSession currentSession(String userId) {
        return sessions.findFirstByWechatUserIdAndActiveTrueOrderByUpdatedAtDesc(userId)
            .orElse(null);
    }

    public String helpText() {
        return """
            直接用日常说法告诉我想怎么修改，不需要记固定命令。

            例如：
            - 标题用黑体二号并居中，正文用宋体小四，两端对齐
            - 把“旧公司”全部换成“ClawBot团队”，再删除空行
            - 文档排得正式一点，行距调成1.5，正文首行缩进
            - 最后另起一页，加一段：以上内容仅供参考
            - 删除包含“风险说明”的段落，然后把文件改名为最终版

            我会按你表达的顺序逐项执行；范围或参数不明确时会先向你确认。
            """.trim();
    }

    private WordDocumentEditResult handleNaturalLanguage(
        WordDocumentSession session, String input
    ) {
        try {
            WordDocumentNaturalLanguagePlanner.PlanResult plan =
                naturalLanguagePlanner.plan(input, session.getExtractedText());
            return switch (plan.status()) {
                case READY -> handleBatch(session, "", plan.commands());
                case CLARIFY -> WordDocumentEditResult.failure(
                    "我需要确认一下：" + plan.message());
                case UNAVAILABLE, INVALID -> null;
            };
        } catch (Exception error) {
            System.err.println("[WORD-DOC] 自然语言规划失败：" + error.getMessage());
            return null;
        }
    }

    public String createdMessage(WordDocumentSession session) {
        return "已收到 Word 文档：" + session.getFileName()
            + "\n\n" + preview(session)
            + "\n\n" + helpText();
    }

    private WordDocumentEditResult handleBatch(
        WordDocumentSession session,
        String input,
        List<WordDocumentCommandParser.ParsedCommand> commands
    ) throws Exception {
        List<String> messages = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (WordDocumentCommandParser.ParsedCommand command : commands) {
            WordDocumentEditResult result = execute(session, command);
            if (!result.success()) {
                failures.add(result.message());
                continue;
            }
            if (result.shouldSendFile()) messages.add(result.message());
            if (command.type() == WordDocumentCommandParser.CommandType.CLEAR) {
                return result;
            }
        }
        List<String> unsupported = WordDocumentCommandParser.unsupportedLines(input);
        String message = buildBatchMessage(messages, unsupported, failures);
        return messages.isEmpty()
            ? WordDocumentEditResult.failure(message)
            : WordDocumentEditResult.success(message, session, true);
    }

    private String buildBatchMessage(
        List<String> messages,
        List<String> unsupported,
        List<String> failures
    ) {
        StringBuilder message = new StringBuilder("已完成 ")
            .append(messages.size())
            .append(" 项 Word 操作：");
        List<String> shownMessages = messages.stream()
            .limit(BATCH_REPORT_LIMIT)
            .toList();
        if (!shownMessages.isEmpty()) {
            message.append("\n- ").append(String.join("\n- ", shownMessages));
        }
        if (messages.size() > BATCH_REPORT_LIMIT) {
            message.append("\n- 其余 ")
                .append(messages.size() - BATCH_REPORT_LIMIT)
                .append(" 项已执行。");
        }
        if (!unsupported.isEmpty()) {
            List<String> shown = unsupported.stream()
                .limit(BATCH_UNSUPPORTED_LIMIT)
                .toList();
            message.append("\n\n以下内容未识别，未执行：\n- ")
                .append(String.join("\n- ", shown));
            if (unsupported.size() > BATCH_UNSUPPORTED_LIMIT) {
                message.append("\n- 其余 ")
                    .append(unsupported.size() - BATCH_UNSUPPORTED_LIMIT)
                    .append(" 条未列出。");
            }
        }
        if (!failures.isEmpty()) {
            List<String> shown = failures.stream()
                .limit(BATCH_UNSUPPORTED_LIMIT)
                .toList();
            message.append("\n\n以下操作执行失败：\n- ")
                .append(String.join("\n- ", shown));
            if (failures.size() > BATCH_UNSUPPORTED_LIMIT) {
                message.append("\n- 其余 ")
                    .append(failures.size() - BATCH_UNSUPPORTED_LIMIT)
                    .append(" 条未列出。");
            }
        }
        return message.toString();
    }

    private WordDocumentEditResult execute(
        WordDocumentSession session,
        WordDocumentCommandParser.ParsedCommand command
    ) throws Exception {
        return switch (command.type()) {
            case VIEW -> WordDocumentEditResult.success(preview(session), session);
            case EXPORT -> WordDocumentEditResult.success(
                "已导出当前 Word 文档。", session, true);
            case CLEAR -> clear(session);
            case CLEAR_CONTENT -> clearContent(session);
            case REPLACE -> replace(session, command.firstValue(), command.secondValue());
            case APPEND -> append(session, command.firstValue());
            case PREPEND -> prepend(session, command.firstValue());
            case DELETE_TEXT -> deleteText(session, command.firstValue());
            case RENAME -> rename(session, command.firstValue());
            case FORMAT_LARGER_CENTER -> format(session, true, true);
            case FORMAT_LARGER -> format(session, true, false);
            case FORMAT_SMALLER -> smallerFont(session);
            case FORMAT_CENTER -> format(session, false, true);
            case FONT_SIZE -> setFontSize(session, command.firstValue());
            case FONT_FAMILY -> setFontFamily(session, command.firstValue());
            case BOLD -> setBold(session, true);
            case UNBOLD -> setBold(session, false);
            case ALIGN_LEFT -> align(session, ParagraphAlignment.LEFT, "已将文档设置为左对齐。");
            case ALIGN_RIGHT -> align(session, ParagraphAlignment.RIGHT, "已将文档设置为右对齐。");
            case ALIGN_BOTH -> align(session, ParagraphAlignment.BOTH, "已将文档设置为两端对齐。");
            case FIRST_LINE_INDENT -> firstLineIndent(session, true);
            case CLEAR_FIRST_LINE_INDENT -> firstLineIndent(session, false);
            case LINE_SPACING -> lineSpacing(session, command.firstValue());
            case PARAGRAPH_SPACING_LARGER -> paragraphSpacingLarger(session);
            case DELETE_EMPTY_LINES -> deleteEmptyLines(session);
            case SET_TITLE -> setTitle(session, command.firstValue());
            case FIRST_LINE_AS_TITLE -> firstLineAsTitle(session);
            case BEAUTIFY -> beautify(session);
            case TITLE_FONT_SIZE -> titleFontSize(session, command.firstValue());
            case BODY_FONT_SIZE -> bodyFontSize(session, command.firstValue());
            case TITLE_FONT_FAMILY -> titleFontFamily(session, command.firstValue());
            case BODY_FONT_FAMILY -> bodyFontFamily(session, command.firstValue());
            case TITLE_BOLD -> titleBold(session);
            case BODY_BOLD -> bodyBold(session, true);
            case BODY_UNBOLD -> bodyBold(session, false);
            case TITLE_CENTER -> titleCenter(session);
            case BODY_CENTER -> bodyAlign(session, ParagraphAlignment.CENTER, "已将正文设置为居中。");
            case BODY_ALIGN_BOTH -> bodyAlign(session, ParagraphAlignment.BOTH, "已将正文设置为两端对齐。");
            case BODY_FIRST_LINE_INDENT -> bodyFirstLineIndent(session);
            case SPACING_BEFORE -> spacingBefore(session, command.firstValue());
            case SPACING_AFTER -> spacingAfter(session, command.firstValue());
            case ADD_HEADING1 -> addHeading1(session, command.firstValue());
            case ADD_HEADING2 -> addHeading2(session, command.firstValue());
            case ADD_PARAGRAPH -> addParagraph(session, command.firstValue());
            case ADD_PAGE_BREAK -> addPageBreak(session);
            case DELETE_PARAGRAPH_CONTAINING -> deleteParagraphContaining(session, command.firstValue());
            case PARAGRAPH_FONT_SIZE -> paragraphFontSize(
                session, command.firstValue(), command.secondValue());
            case PARAGRAPH_FONT_FAMILY -> paragraphFontFamily(
                session, command.firstValue(), command.secondValue());
            case PARAGRAPH_BOLD -> paragraphBold(session, command.firstValue(), true);
            case PARAGRAPH_UNBOLD -> paragraphBold(session, command.firstValue(), false);
            case PARAGRAPH_ALIGN -> paragraphAlign(
                session, command.firstValue(), command.secondValue());
            case MATCHING_PARAGRAPH_FONT_SIZE -> matchingParagraphFontSize(
                session, command.firstValue(), command.secondValue());
            case MATCHING_PARAGRAPH_FONT_FAMILY -> matchingParagraphFontFamily(
                session, command.firstValue(), command.secondValue());
            case MATCHING_PARAGRAPH_BOLD -> matchingParagraphBold(
                session, command.firstValue(), true);
            case MATCHING_PARAGRAPH_UNBOLD -> matchingParagraphBold(
                session, command.firstValue(), false);
            case MATCHING_PARAGRAPH_ALIGN -> matchingParagraphAlign(
                session, command.firstValue(), command.secondValue());
            case HELP -> WordDocumentEditResult.success(helpText(), session);
            case UNKNOWN -> WordDocumentEditResult.failure("无法识别文档操作。\n" + helpText());
        };
    }

    private WordDocumentEditResult replace(
        WordDocumentSession session, String oldText, String newText
    ) throws Exception {
        if (!hasText(oldText)) return WordDocumentEditResult.failure("请提供要替换的旧内容。");
        String sourceText = session.getExtractedText() == null ? "" : session.getExtractedText();
        if (!sourceText.contains(oldText)) {
            return WordDocumentEditResult.failure("文档中没有找到要替换的内容：" + oldText);
        }
        byte[] next = editor.replace(session.getContent(), oldText, newText == null ? "" : newText);
        updateContent(session, next);
        return changed("已替换：" + oldText + " => " + newText, session);
    }

    private WordDocumentEditResult append(WordDocumentSession session, String text)
        throws Exception {
        if (!hasText(text)) return WordDocumentEditResult.failure("请提供要追加的内容。");
        byte[] next = editor.append(session.getContent(), text.trim());
        updateContent(session, next);
        return changed("已在文末追加内容。", session);
    }

    private WordDocumentEditResult prepend(WordDocumentSession session, String text)
        throws Exception {
        if (!hasText(text)) return WordDocumentEditResult.failure("请提供要添加到开头的内容。");
        byte[] next = editor.prepend(session.getContent(), text.trim());
        updateContent(session, next);
        return changed("已在文档开头添加内容。", session);
    }

    private WordDocumentEditResult deleteText(WordDocumentSession session, String text)
        throws Exception {
        if (!hasText(text)) return WordDocumentEditResult.failure("请提供要删除的内容。");
        String sourceText = session.getExtractedText() == null ? "" : session.getExtractedText();
        if (!sourceText.contains(text)) {
            return WordDocumentEditResult.failure("文档中没有找到要删除的内容：" + text);
        }
        byte[] next = editor.deleteText(session.getContent(), text.trim());
        updateContent(session, next);
        return changed("已删除指定内容。", session);
    }

    private WordDocumentEditResult rename(WordDocumentSession session, String fileName) {
        if (!hasText(fileName)) return WordDocumentEditResult.failure("请提供新的文件名。");
        String normalized = fileName.trim();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            normalized += ".docx";
        }
        session.setFileName(normalized);
        session.setUpdatedAt(Instant.now());
        sessions.save(session);
        return changed("已重命名为：" + normalized, session);
    }

    private WordDocumentEditResult format(
        WordDocumentSession session, boolean largerFont, boolean center
    ) throws Exception {
        byte[] next = editor.format(session.getContent(), largerFont, center);
        updateContent(session, next);
        String message = largerFont && center
            ? "已将文档字体调大，并设置为居中。"
            : largerFont ? "已将文档字体调大。" : "已将文档内容设置为居中。";
        return changed(message, session);
    }

    private WordDocumentEditResult clearContent(WordDocumentSession session) throws Exception {
        updateContent(session, editor.clearContent(session.getContent()));
        return changed("已清空文档内容。", session);
    }

    private WordDocumentEditResult smallerFont(WordDocumentSession session) throws Exception {
        updateContent(session, editor.smallerFont(session.getContent()));
        return changed("已将文档字体调小。", session);
    }

    private WordDocumentEditResult setFontSize(WordDocumentSession session, String size)
        throws Exception {
        int fontSize = parseInt(size, 1, 72, "字号需要是 1 到 72 之间的数字。");
        updateContent(session, editor.setFontSize(session.getContent(), fontSize));
        return changed("已将文档字号设置为：" + fontSize, session);
    }

    private WordDocumentEditResult setFontFamily(WordDocumentSession session, String fontFamily)
        throws Exception {
        if (!hasText(fontFamily)) return WordDocumentEditResult.failure("请提供字体名称。");
        updateContent(session, editor.setFontFamily(session.getContent(), fontFamily.trim()));
        return changed("已将文档字体设置为：" + fontFamily.trim(), session);
    }

    private WordDocumentEditResult setBold(WordDocumentSession session, boolean bold)
        throws Exception {
        updateContent(session, editor.setBold(session.getContent(), bold));
        return changed(bold ? "已将文档文字加粗。" : "已取消文档文字加粗。", session);
    }

    private WordDocumentEditResult align(
        WordDocumentSession session, ParagraphAlignment alignment, String message
    ) throws Exception {
        updateContent(session, editor.align(session.getContent(), alignment));
        return changed(message, session);
    }

    private WordDocumentEditResult firstLineIndent(WordDocumentSession session, boolean enabled)
        throws Exception {
        updateContent(session, editor.setFirstLineIndent(session.getContent(), enabled));
        return changed(enabled ? "已设置首行缩进。" : "已取消首行缩进。", session);
    }

    private WordDocumentEditResult lineSpacing(WordDocumentSession session, String value)
        throws Exception {
        double spacing = parseDouble(value, 0.5, 5, "行距需要是 0.5 到 5 之间的数字。");
        updateContent(session, editor.setLineSpacing(session.getContent(), spacing));
        return changed("已将文档行距设置为：" + spacing, session);
    }

    private WordDocumentEditResult paragraphSpacingLarger(WordDocumentSession session)
        throws Exception {
        updateContent(session, editor.enlargeParagraphSpacing(session.getContent()));
        return changed("已将段落间距调大。", session);
    }

    private WordDocumentEditResult deleteEmptyLines(WordDocumentSession session)
        throws Exception {
        updateContent(session, editor.deleteEmptyLines(session.getContent()));
        return changed("已删除空行。", session);
    }

    private WordDocumentEditResult setTitle(WordDocumentSession session, String title)
        throws Exception {
        if (!hasText(title)) return WordDocumentEditResult.failure("请提供标题内容。");
        updateContent(session, editor.setTitle(session.getContent(), title.trim()));
        return changed("已设置标题：" + title.trim(), session);
    }

    private WordDocumentEditResult firstLineAsTitle(WordDocumentSession session)
        throws Exception {
        updateContent(session, editor.firstLineAsTitle(session.getContent()));
        return changed("已将第一行设置为标题样式。", session);
    }

    private WordDocumentEditResult beautify(WordDocumentSession session) throws Exception {
        updateContent(session, editor.beautify(session.getContent()));
        return changed("已按正式文档样式美化排版。", session);
    }

    private WordDocumentEditResult titleFontSize(WordDocumentSession session, String size)
        throws Exception {
        int fontSize = parseInt(size, 1, 72, "标题字号需要是 1 到 72 之间的数字。");
        updateContent(session, editor.setTitleFontSize(session.getContent(), fontSize));
        return changed("已将标题字号设置为：" + fontSize, session);
    }

    private WordDocumentEditResult bodyFontSize(WordDocumentSession session, String size)
        throws Exception {
        int fontSize = parseInt(size, 1, 72, "正文字号需要是 1 到 72 之间的数字。");
        updateContent(session, editor.setBodyFontSize(session.getContent(), fontSize));
        return changed("已将正文字号设置为：" + fontSize, session);
    }

    private WordDocumentEditResult titleFontFamily(WordDocumentSession session, String fontFamily)
        throws Exception {
        if (!hasText(fontFamily)) return WordDocumentEditResult.failure("请提供标题字体名称。");
        updateContent(session, editor.setTitleFontFamily(session.getContent(), fontFamily.trim()));
        return changed("已将标题字体设置为：" + fontFamily.trim(), session);
    }

    private WordDocumentEditResult bodyFontFamily(WordDocumentSession session, String fontFamily)
        throws Exception {
        if (!hasText(fontFamily)) return WordDocumentEditResult.failure("请提供正文字体名称。");
        updateContent(session, editor.setBodyFontFamily(session.getContent(), fontFamily.trim()));
        return changed("已将正文字体设置为：" + fontFamily.trim(), session);
    }

    private WordDocumentEditResult titleBold(WordDocumentSession session) throws Exception {
        updateContent(session, editor.setTitleBold(session.getContent(), true));
        return changed("已将标题加粗。", session);
    }

    private WordDocumentEditResult bodyBold(WordDocumentSession session, boolean bold)
        throws Exception {
        updateContent(session, editor.setBodyBold(session.getContent(), bold));
        return changed(bold ? "已将正文加粗。" : "已取消正文加粗。", session);
    }

    private WordDocumentEditResult titleCenter(WordDocumentSession session) throws Exception {
        updateContent(session, editor.alignTitle(session.getContent(), ParagraphAlignment.CENTER));
        return changed("已将标题设置为居中。", session);
    }

    private WordDocumentEditResult bodyAlign(
        WordDocumentSession session, ParagraphAlignment alignment, String message
    ) throws Exception {
        updateContent(session, editor.alignBody(session.getContent(), alignment));
        return changed(message, session);
    }

    private WordDocumentEditResult bodyFirstLineIndent(WordDocumentSession session)
        throws Exception {
        updateContent(session, editor.setBodyFirstLineIndent(session.getContent(), true));
        return changed("已设置正文首行缩进。", session);
    }

    private WordDocumentEditResult spacingBefore(WordDocumentSession session, String value)
        throws Exception {
        int points = parseInt(value, 0, 72, "段前间距需要是 0 到 72 之间的数字。");
        updateContent(session, editor.setSpacingBefore(session.getContent(), points));
        return changed("已将段前间距设置为：" + points, session);
    }

    private WordDocumentEditResult spacingAfter(WordDocumentSession session, String value)
        throws Exception {
        int points = parseInt(value, 0, 72, "段后间距需要是 0 到 72 之间的数字。");
        updateContent(session, editor.setSpacingAfter(session.getContent(), points));
        return changed("已将段后间距设置为：" + points, session);
    }

    private WordDocumentEditResult addHeading1(WordDocumentSession session, String text)
        throws Exception {
        if (!hasText(text)) return WordDocumentEditResult.failure("请提供一级标题内容。");
        updateContent(session, editor.addHeading1(session.getContent(), text.trim()));
        return changed("已添加一级标题：" + text.trim(), session);
    }

    private WordDocumentEditResult addHeading2(WordDocumentSession session, String text)
        throws Exception {
        if (!hasText(text)) return WordDocumentEditResult.failure("请提供二级标题内容。");
        updateContent(session, editor.addHeading2(session.getContent(), text.trim()));
        return changed("已添加二级标题：" + text.trim(), session);
    }

    private WordDocumentEditResult addParagraph(WordDocumentSession session, String text)
        throws Exception {
        if (!hasText(text)) return WordDocumentEditResult.failure("请提供段落内容。");
        updateContent(session, editor.addParagraph(session.getContent(), text.trim()));
        return changed("已添加段落。", session);
    }

    private WordDocumentEditResult addPageBreak(WordDocumentSession session)
        throws Exception {
        updateContent(session, editor.addPageBreak(session.getContent()));
        return changed("已添加分页。", session);
    }

    private WordDocumentEditResult deleteParagraphContaining(
        WordDocumentSession session, String keyword
    ) throws Exception {
        if (!hasText(keyword)) return WordDocumentEditResult.failure("请提供要匹配的段落关键词。");
        String sourceText = session.getExtractedText() == null ? "" : session.getExtractedText();
        if (!sourceText.contains(keyword)) {
            return WordDocumentEditResult.failure("没有找到包含该关键词的段落：" + keyword);
        }
        updateContent(session, editor.deleteParagraphContaining(session.getContent(), keyword.trim()));
        return changed("已删除包含“" + keyword.trim() + "”的段落。", session);
    }

    private WordDocumentEditResult paragraphFontSize(
        WordDocumentSession session, String index, String size
    ) throws Exception {
        int paragraph = parseParagraphIndex(session, index);
        int fontSize = parseInt(size, 1, 72, "段落字号需要是 1 到 72 之间的数字。");
        updateContent(session, editor.setParagraphFontSize(
            session.getContent(), paragraph, fontSize));
        return changed("已将第 " + paragraph + " 段字号设置为 " + fontSize + "。", session);
    }

    private WordDocumentEditResult paragraphFontFamily(
        WordDocumentSession session, String index, String fontFamily
    ) throws Exception {
        int paragraph = parseParagraphIndex(session, index);
        if (!hasText(fontFamily)) throw new IllegalArgumentException("请提供字体名称。");
        updateContent(session, editor.setParagraphFontFamily(
            session.getContent(), paragraph, fontFamily.trim()));
        return changed("已将第 " + paragraph + " 段字体设置为 " + fontFamily.trim() + "。", session);
    }

    private WordDocumentEditResult paragraphBold(
        WordDocumentSession session, String index, boolean bold
    ) throws Exception {
        int paragraph = parseParagraphIndex(session, index);
        updateContent(session, editor.setParagraphBold(session.getContent(), paragraph, bold));
        return changed("已" + (bold ? "加粗" : "取消加粗") + "第 " + paragraph + " 段。", session);
    }

    private WordDocumentEditResult paragraphAlign(
        WordDocumentSession session, String index, String alignment
    ) throws Exception {
        int paragraph = parseParagraphIndex(session, index);
        ParagraphAlignment value = parseAlignment(alignment);
        updateContent(session, editor.alignParagraph(session.getContent(), paragraph, value));
        return changed("已调整第 " + paragraph + " 段的对齐方式。", session);
    }

    private WordDocumentEditResult matchingParagraphFontSize(
        WordDocumentSession session, String keyword, String size
    ) throws Exception {
        requireKeyword(session, keyword);
        int fontSize = parseInt(size, 1, 72, "段落字号需要是 1 到 72 之间的数字。");
        updateContent(session, editor.setMatchingParagraphFontSize(
            session.getContent(), keyword.trim(), fontSize));
        return changed("已调整包含“" + keyword.trim() + "”的段落字号。", session);
    }

    private WordDocumentEditResult matchingParagraphFontFamily(
        WordDocumentSession session, String keyword, String fontFamily
    ) throws Exception {
        requireKeyword(session, keyword);
        if (!hasText(fontFamily)) throw new IllegalArgumentException("请提供字体名称。");
        updateContent(session, editor.setMatchingParagraphFontFamily(
            session.getContent(), keyword.trim(), fontFamily.trim()));
        return changed("已调整包含“" + keyword.trim() + "”的段落字体。", session);
    }

    private WordDocumentEditResult matchingParagraphBold(
        WordDocumentSession session, String keyword, boolean bold
    ) throws Exception {
        requireKeyword(session, keyword);
        updateContent(session, editor.setMatchingParagraphBold(
            session.getContent(), keyword.trim(), bold));
        return changed("已" + (bold ? "加粗" : "取消加粗")
            + "包含“" + keyword.trim() + "”的段落。", session);
    }

    private WordDocumentEditResult matchingParagraphAlign(
        WordDocumentSession session, String keyword, String alignment
    ) throws Exception {
        requireKeyword(session, keyword);
        updateContent(session, editor.alignMatchingParagraph(
            session.getContent(), keyword.trim(), parseAlignment(alignment)));
        return changed("已调整包含“" + keyword.trim() + "”的段落对齐方式。", session);
    }

    private int parseParagraphIndex(WordDocumentSession session, String value) {
        int count = session.getExtractedText() == null || session.getExtractedText().isBlank()
            ? 0 : (int) session.getExtractedText().lines().filter(line -> !line.isBlank()).count();
        return parseInt(value, 1, Math.max(1, count),
            "段落序号超出范围，当前文档共有 " + count + " 个非空段落。");
    }

    private void requireKeyword(WordDocumentSession session, String keyword) {
        if (!hasText(keyword) || session.getExtractedText() == null
            || !session.getExtractedText().contains(keyword.trim())) {
            throw new IllegalArgumentException("文档中没有找到用于定位段落的内容：" + keyword);
        }
    }

    private ParagraphAlignment parseAlignment(String value) {
        try {
            return ParagraphAlignment.valueOf(value == null
                ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            throw new IllegalArgumentException("对齐方式只支持左对齐、右对齐、居中或两端对齐。");
        }
    }

    private WordDocumentEditResult clear(WordDocumentSession session) {
        session.setActive(false);
        session.setUpdatedAt(Instant.now());
        sessions.save(session);
        return WordDocumentEditResult.success("已删除当前 Word 文档会话。", session);
    }

    private void updateContent(WordDocumentSession session, byte[] bytes) throws Exception {
        session.setContent(bytes);
        session.setExtractedText(editor.extractText(bytes));
        session.setUpdatedAt(Instant.now());
        sessions.save(session);
    }

    private String preview(WordDocumentSession session) {
        String text = session.getExtractedText();
        if (text == null || text.isBlank()) return "文档当前没有可预览文本。";
        String preview = text.length() > PREVIEW_LIMIT
            ? text.substring(0, PREVIEW_LIMIT) + "..."
            : text;
        return "文档预览：\n" + preview;
    }

    private boolean isDocx(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private WordDocumentEditResult changed(String message, WordDocumentSession session) {
        return WordDocumentEditResult.success(message, session, true);
    }

    private int parseInt(String value, int min, int max, String errorMessage) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(errorMessage);
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private double parseDouble(String value, double min, double max, String errorMessage) {
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(errorMessage);
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
