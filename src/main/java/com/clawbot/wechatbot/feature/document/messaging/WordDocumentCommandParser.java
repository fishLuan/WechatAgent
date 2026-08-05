package com.clawbot.wechatbot.feature.document.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Word 文档多轮编辑命令解析。 */
public final class WordDocumentCommandParser {
    private WordDocumentCommandParser() {
    }

    public enum CommandType {
        VIEW,
        REPLACE,
        APPEND,
        DELETE_TEXT,
        RENAME,
        EXPORT,
        CLEAR,
        HELP,
        PREPEND,
        CLEAR_CONTENT,
        FONT_SIZE,
        FONT_FAMILY,
        BOLD,
        UNBOLD,
        FORMAT_LARGER_CENTER,
        FORMAT_LARGER,
        FORMAT_SMALLER,
        FORMAT_CENTER,
        ALIGN_LEFT,
        ALIGN_RIGHT,
        ALIGN_BOTH,
        FIRST_LINE_INDENT,
        CLEAR_FIRST_LINE_INDENT,
        LINE_SPACING,
        PARAGRAPH_SPACING_LARGER,
        DELETE_EMPTY_LINES,
        SET_TITLE,
        FIRST_LINE_AS_TITLE,
        BEAUTIFY,
        TITLE_FONT_SIZE,
        BODY_FONT_SIZE,
        TITLE_FONT_FAMILY,
        BODY_FONT_FAMILY,
        TITLE_BOLD,
        BODY_BOLD,
        BODY_UNBOLD,
        TITLE_CENTER,
        BODY_CENTER,
        BODY_ALIGN_BOTH,
        BODY_FIRST_LINE_INDENT,
        SPACING_BEFORE,
        SPACING_AFTER,
        ADD_HEADING1,
        ADD_HEADING2,
        ADD_PARAGRAPH,
        ADD_PAGE_BREAK,
        DELETE_PARAGRAPH_CONTAINING,
        PARAGRAPH_FONT_SIZE,
        PARAGRAPH_FONT_FAMILY,
        PARAGRAPH_BOLD,
        PARAGRAPH_UNBOLD,
        PARAGRAPH_ALIGN,
        MATCHING_PARAGRAPH_FONT_SIZE,
        MATCHING_PARAGRAPH_FONT_FAMILY,
        MATCHING_PARAGRAPH_BOLD,
        MATCHING_PARAGRAPH_UNBOLD,
        MATCHING_PARAGRAPH_ALIGN,
        UNKNOWN
    }

    public record ParsedCommand(
        CommandType type,
        String firstValue,
        String secondValue
    ) {
        static ParsedCommand of(CommandType type) {
            return new ParsedCommand(type, null, null);
        }
    }

    private static final Pattern REPLACE = Pattern.compile(
        "^(?:替换|修改|改)\\s*[：:]\\s*(.+?)\\s*(?:=>|->|为|成)\\s*(.+?)\\s*$");
    private static final Pattern APPEND = Pattern.compile(
        "^(?:追加|新增|添加|补充)\\s*[：:]\\s*(.+?)\\s*$");
    private static final Pattern PREPEND = Pattern.compile(
        "^(?:开头添加|在开头添加|前面添加)\\s*[：:]\\s*(.+?)\\s*$");
    private static final Pattern DELETE = Pattern.compile(
        "^(?:删除|删掉|移除)\\s*[：:]\\s*(.+?)\\s*$");
    private static final Pattern RENAME = Pattern.compile(
        "^(?:重命名|改名)\\s*[：:]\\s*(.+?)\\s*$");
    private static final Pattern FONT_SIZE = Pattern.compile(
        "^(?:字号|字体大小|全文字号|把字号(?:调到|改成|设为|设置为)?)\\s*[：:]?\\s*(\\d{1,2})\\s*(?:号|pt)?\\s*$");
    private static final Pattern TITLE_FONT_SIZE = Pattern.compile(
        "^(?:标题字号|标题字体大小|把标题字号(?:调到|改成|设为|设置为)?|标题(?:放大|调大|改大|字号调到|字号改成|字号设为|字号设置为))\\s*[：:]?\\s*(?:到)?\\s*(\\d{1,2})\\s*(?:号|pt)?\\s*$");
    private static final Pattern BODY_FONT_SIZE = Pattern.compile(
        "^(?:正文字号|正文字体大小|把正文字号(?:调到|改成|设为|设置为)?|正文(?:字号调到|字号改成|字号设为|字号设置为))\\s*[：:]?\\s*(?:到)?\\s*(\\d{1,2})\\s*(?:号|pt)?\\s*$");
    private static final Pattern FONT_FAMILY = Pattern.compile(
        "^(?:字体|全文字体|改字体|设置字体|把字体(?:改成|设为|设置为)?)\\s*[：:]?\\s*(.+?)\\s*$");
    private static final Pattern TITLE_FONT_FAMILY = Pattern.compile(
        "^(?:标题字体|把标题字体(?:改成|设为|设置为)?)\\s*[：:]?\\s*(.+?)\\s*$");
    private static final Pattern BODY_FONT_FAMILY = Pattern.compile(
        "^(?:正文字体|把正文字体(?:改成|设为|设置为)?|正文(?:改成|设为|设置为))\\s*[：:]?\\s*(.+?)\\s*$");
    private static final Pattern LINE_SPACING = Pattern.compile(
        "^(?:行距|设置行距|把行距(?:调到|改成|设为|设置为)?)\\s*[：:]?\\s*(\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern SPACING_BEFORE = Pattern.compile(
        "^(?:段前间距|段前)\\s*[：:]?\\s*(\\d{1,3})\\s*$");
    private static final Pattern SPACING_AFTER = Pattern.compile(
        "^(?:段后间距|段后)\\s*[：:]?\\s*(\\d{1,3})\\s*$");
    private static final Pattern SET_TITLE = Pattern.compile(
        "^(?:设置标题|标题|把标题(?:改成|设为|设置为))\\s*[：:]\\s*(.+?)\\s*$");
    private static final Pattern ADD_HEADING1 = Pattern.compile(
        "^(?:添加一级标题|新增一级标题|加一级标题)\\s*[：:]?\\s*(.+?)\\s*$");
    private static final Pattern ADD_HEADING2 = Pattern.compile(
        "^(?:添加二级标题|新增二级标题|加二级标题|添加小标题|新增小标题)\\s*[：:]?\\s*(.+?)\\s*$");
    private static final Pattern ADD_PARAGRAPH = Pattern.compile(
        "^(?:添加段落|新增段落|加一段|再加一段|帮我加一段|写一段)\\s*[：:]?\\s*(.+?)\\s*$");
    private static final Pattern DELETE_PARAGRAPH_CONTAINING = Pattern.compile(
        "^(?:删除包含|删掉包含|删除含有|删掉含有)\\s*[“\"']?(.+?)[”\"']?\\s*(?:的段落)?$");

    public static ParsedCommand parse(String input) {
        if (input == null || input.isBlank()) {
            return ParsedCommand.of(CommandType.UNKNOWN);
        }
        String text = normalizeLine(input);
        text = stripPolitePrefix(text);
        if (text.matches("^(查看文档|查看内容|预览文档|文档预览)$")) {
            return ParsedCommand.of(CommandType.VIEW);
        }
        if (text.matches("^(导出Word|导出word|导出文档|发送Word|发送word)$")) {
            return ParsedCommand.of(CommandType.EXPORT);
        }
        if (text.matches("^(删除文档|清空文档|关闭文档|取消文档)$")) {
            return ParsedCommand.of(CommandType.CLEAR);
        }
        if (text.matches("^(清空文档内容|清空内容)$")) {
            return ParsedCommand.of(CommandType.CLEAR_CONTENT);
        }
        if (text.matches("^(文档帮助|word帮助|Word帮助|文档格式|操作格式)$")) {
            return ParsedCommand.of(CommandType.HELP);
        }
        if (text.matches("^(美化排版|整理格式|改成正式文档格式|帮我美化一下|排版美观一点)$")) {
            return ParsedCommand.of(CommandType.BEAUTIFY);
        }
        if (text.matches("^(加粗|全文加粗|都加粗|把全文加粗)$")) {
            return ParsedCommand.of(CommandType.BOLD);
        }
        if (text.matches("^(取消加粗|去掉加粗|全文取消加粗)$")) {
            return ParsedCommand.of(CommandType.UNBOLD);
        }
        if (text.matches("^(标题加粗|把标题加粗)$")) {
            return ParsedCommand.of(CommandType.TITLE_BOLD);
        }
        if (text.matches("^(正文加粗|把正文加粗)$")) {
            return ParsedCommand.of(CommandType.BODY_BOLD);
        }
        if (text.matches("^(正文取消加粗|正文去掉加粗)$")) {
            return ParsedCommand.of(CommandType.BODY_UNBOLD);
        }
        Matcher matcher = TITLE_FONT_SIZE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.TITLE_FONT_SIZE, matcher.group(1).trim(), null);
        }
        matcher = BODY_FONT_SIZE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.BODY_FONT_SIZE, matcher.group(1).trim(), null);
        }
        matcher = FONT_SIZE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.FONT_SIZE, matcher.group(1).trim(), null);
        }
        if (text.matches("^(字体小一点|字号小一点|缩小字体)$")) {
            return ParsedCommand.of(CommandType.FORMAT_SMALLER);
        }
        if (containsAny(text, "大一点", "放大", "调大", "改大")
            && containsAny(text, "居中", "居中对齐", "内容居中")) {
            return ParsedCommand.of(CommandType.FORMAT_LARGER_CENTER);
        }
        if (containsAny(text, "大一点", "放大", "调大", "改大")) {
            return ParsedCommand.of(CommandType.FORMAT_LARGER);
        }
        matcher = TITLE_FONT_FAMILY.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.TITLE_FONT_FAMILY, matcher.group(1).trim(), null);
        }
        matcher = BODY_FONT_FAMILY.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.BODY_FONT_FAMILY, matcher.group(1).trim(), null);
        }
        matcher = FONT_FAMILY.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.FONT_FAMILY, matcher.group(1).trim(), null);
        }
        matcher = LINE_SPACING.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.LINE_SPACING, matcher.group(1).trim(), null);
        }
        matcher = SPACING_BEFORE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.SPACING_BEFORE, matcher.group(1).trim(), null);
        }
        matcher = SPACING_AFTER.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.SPACING_AFTER, matcher.group(1).trim(), null);
        }
        matcher = SET_TITLE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.SET_TITLE, matcher.group(1).trim(), null);
        }
        if (text.matches("^(标题居中|把标题居中|标题居中对齐)$")) {
            return ParsedCommand.of(CommandType.TITLE_CENTER);
        }
        if (text.matches("^(正文居中|把正文居中|正文居中对齐)$")) {
            return ParsedCommand.of(CommandType.BODY_CENTER);
        }
        if (containsAny(text, "居中", "居中对齐", "内容居中")) {
            return ParsedCommand.of(CommandType.FORMAT_CENTER);
        }
        if (text.matches("^(左对齐|全文左对齐)$")) {
            return ParsedCommand.of(CommandType.ALIGN_LEFT);
        }
        if (text.matches("^(右对齐|全文右对齐)$")) {
            return ParsedCommand.of(CommandType.ALIGN_RIGHT);
        }
        if (text.matches("^(两端对齐|全文两端对齐)$")) {
            return ParsedCommand.of(CommandType.ALIGN_BOTH);
        }
        if (text.matches("^(正文两端对齐|把正文两端对齐)$")) {
            return ParsedCommand.of(CommandType.BODY_ALIGN_BOTH);
        }
        if (text.matches("^(首行缩进|正文首行缩进)$")) {
            return ParsedCommand.of(text.contains("正文")
                ? CommandType.BODY_FIRST_LINE_INDENT
                : CommandType.FIRST_LINE_INDENT);
        }
        if (text.matches("^(取消首行缩进|去掉首行缩进)$")) {
            return ParsedCommand.of(CommandType.CLEAR_FIRST_LINE_INDENT);
        }
        if (text.matches("^(段落间距大一点|段间距大一点)$")) {
            return ParsedCommand.of(CommandType.PARAGRAPH_SPACING_LARGER);
        }
        if (text.matches("^(删除空行|清理空行)$")) {
            return ParsedCommand.of(CommandType.DELETE_EMPTY_LINES);
        }
        if (text.matches("^(把第一行设为标题|第一行设为标题)$")) {
            return ParsedCommand.of(CommandType.FIRST_LINE_AS_TITLE);
        }
        if (text.matches("^(添加分页|加分页|分页|另起一页)$")) {
            return ParsedCommand.of(CommandType.ADD_PAGE_BREAK);
        }

        matcher = REPLACE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(
                CommandType.REPLACE, matcher.group(1).trim(), matcher.group(2).trim());
        }
        matcher = APPEND.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.APPEND, matcher.group(1).trim(), null);
        }
        matcher = PREPEND.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.PREPEND, matcher.group(1).trim(), null);
        }
        matcher = DELETE.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.DELETE_TEXT, matcher.group(1).trim(), null);
        }
        matcher = RENAME.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.RENAME, matcher.group(1).trim(), null);
        }
        matcher = ADD_HEADING1.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.ADD_HEADING1, matcher.group(1).trim(), null);
        }
        matcher = ADD_HEADING2.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.ADD_HEADING2, matcher.group(1).trim(), null);
        }
        matcher = ADD_PARAGRAPH.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(CommandType.ADD_PARAGRAPH, matcher.group(1).trim(), null);
        }
        matcher = DELETE_PARAGRAPH_CONTAINING.matcher(text);
        if (matcher.matches()) {
            return new ParsedCommand(
                CommandType.DELETE_PARAGRAPH_CONTAINING, matcher.group(1).trim(), null);
        }
        return ParsedCommand.of(CommandType.UNKNOWN);
    }

    public static boolean looksLikeWordDocumentCommand(String input) {
        return parse(input).type() != CommandType.UNKNOWN;
    }

    public static List<ParsedCommand> parseMany(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<ParsedCommand> commands = new ArrayList<>();
        for (String rawLine : splitCommandLines(input)) {
            String line = normalizeLine(rawLine);
            if (line.isBlank()) continue;
            ParsedCommand command = parse(line);
            if (command.type() != CommandType.UNKNOWN) {
                commands.add(command);
            }
        }
        return commands;
    }

    public static boolean looksLikeWordDocumentCommandBatch(String input) {
        return !parseMany(input).isEmpty() || looksLikeWordDocumentCommand(input);
    }

    /**
     * 提取明确表示“先说需求、随后上传 Word”消息中的实际编辑指令。
     * 普通历史命令不会进入待上传缓存，避免下一份文档误用旧上下文。
     */
    public static String extractPendingFileInstruction(String input) {
        if (input == null || input.isBlank()) return null;
        String text = input.trim();
        boolean mentionsFile = containsAny(text,
            "文档", "文件", "Word", "word", ".docx");
        boolean mentionsFutureUpload = containsAny(text,
            "等下发", "待会发", "稍后发", "马上发", "一会儿发", "随后发",
            "接下来发", "下面发", "下一条发", "上传后", "发过去后",
            "接下来上传", "稍后上传", "马上上传");
        if (!mentionsFile || !mentionsFutureUpload) return null;

        String commandText = text;
        int separator = firstInstructionSeparator(text);
        if (separator >= 0 && separator + 1 < text.length()) {
            commandText = text.substring(separator + 1).trim();
        } else {
            commandText = text.replaceFirst(
                "^.*?(?:文档|文件|Word|word|\\.docx)\\s*(?:后|之后)?\\s*", "");
        }
        commandText = stripPolitePrefix(commandText);
        return looksLikeWordDocumentCommandBatch(commandText) ? commandText : null;
    }

    public static List<String> unsupportedLines(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<String> unsupported = new ArrayList<>();
        for (String rawLine : splitCommandLines(input)) {
            String line = normalizeLine(rawLine);
            if (line.isBlank()) continue;
            if (parse(line).type() == CommandType.UNKNOWN) {
                unsupported.add(line);
            }
        }
        return unsupported;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static int firstInstructionSeparator(String text) {
        int result = -1;
        for (char separator : new char[] {'，', ',', '：', ':', '；', ';'}) {
            int index = text.indexOf(separator);
            if (index >= 0 && (result < 0 || index < result)) result = index;
        }
        return result;
    }

    private static String normalizeLine(String input) {
        String text = input == null ? "" : input.trim();
        return text.replaceFirst("^[\\-•*\\s]*(?:\\d+[.、)]\\s*)?", "").trim();
    }

    private static String[] splitCommandLines(String input) {
        return input
            .replace("，然后", "\n")
            .replace("，再", "\n")
            .replace("，并且", "\n")
            .replace("，同时", "\n")
            .replaceAll("，(?=(?:标题|正文|字体|字号|居中|加粗|首行|段前|段后|替换|添加|新增|删除|美化|行距|导出|重命名|把))", "\n")
            .replace('；', '\n')
            .replace(';', '\n')
            .split("\\R");
    }

    private static String stripPolitePrefix(String text) {
        String normalized = text;
        String next;
        do {
            next = normalized.replaceFirst("^(?:麻烦|请|帮我|帮忙|给我|把)\\s*", "").trim();
            if (next.equals(normalized)) return normalized;
            normalized = next;
        } while (!normalized.isBlank());
        return normalized;
    }
}
