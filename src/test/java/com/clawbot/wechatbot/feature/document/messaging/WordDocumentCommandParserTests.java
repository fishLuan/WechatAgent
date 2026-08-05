package com.clawbot.wechatbot.feature.document.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordDocumentCommandParserTests {

    @Test
    void parsesBasicCommands() {
        assertEquals(
            WordDocumentCommandParser.CommandType.VIEW,
            WordDocumentCommandParser.parse("查看文档").type());
        assertEquals(
            WordDocumentCommandParser.CommandType.EXPORT,
            WordDocumentCommandParser.parse("导出Word").type());
        assertEquals(
            WordDocumentCommandParser.CommandType.CLEAR,
            WordDocumentCommandParser.parse("删除文档").type());
    }

    @Test
    void parsesEditCommands() {
        WordDocumentCommandParser.ParsedCommand replace =
            WordDocumentCommandParser.parse("替换：张三 => 李四");
        assertEquals(WordDocumentCommandParser.CommandType.REPLACE, replace.type());
        assertEquals("张三", replace.firstValue());
        assertEquals("李四", replace.secondValue());

        WordDocumentCommandParser.ParsedCommand append =
            WordDocumentCommandParser.parse("追加：以上内容仅供参考。");
        assertEquals(WordDocumentCommandParser.CommandType.APPEND, append.type());
        assertEquals("以上内容仅供参考。", append.firstValue());

        WordDocumentCommandParser.ParsedCommand delete =
            WordDocumentCommandParser.parse("删除：临时内容");
        assertEquals(WordDocumentCommandParser.CommandType.DELETE_TEXT, delete.type());
        assertEquals("临时内容", delete.firstValue());
    }

    @Test
    void detectsWordDocumentCommands() {
        assertTrue(WordDocumentCommandParser.looksLikeWordDocumentCommand("操作格式"));
        assertTrue(WordDocumentCommandParser.looksLikeWordDocumentCommand("重命名：新版合同"));
        assertEquals(
            WordDocumentCommandParser.CommandType.FORMAT_LARGER_CENTER,
            WordDocumentCommandParser.parse("帮我把字体修改的大一点，然后把内容居中").type());
    }

    @Test
    void parsesCommonFormatCommands() {
        assertEquals(
            WordDocumentCommandParser.CommandType.FONT_SIZE,
            WordDocumentCommandParser.parse("字号：18").type());
        assertEquals(
            WordDocumentCommandParser.CommandType.FONT_FAMILY,
            WordDocumentCommandParser.parse("字体：微软雅黑").type());
        assertEquals(
            WordDocumentCommandParser.CommandType.LINE_SPACING,
            WordDocumentCommandParser.parse("行距：1.5").type());
        assertEquals(
            WordDocumentCommandParser.CommandType.FIRST_LINE_AS_TITLE,
            WordDocumentCommandParser.parse("把第一行设为标题").type());
        assertEquals(
            WordDocumentCommandParser.CommandType.BEAUTIFY,
            WordDocumentCommandParser.parse("美化排版").type());
    }

    @Test
    void parsesMultiLineCommandBatch() {
        var commands = WordDocumentCommandParser.parseMany("""
            字号：18
            字体：微软雅黑
            居中
            替换：旧公司名 => ClawBot团队
            美化排版
            """);

        assertEquals(5, commands.size());
        assertEquals(WordDocumentCommandParser.CommandType.FONT_SIZE, commands.get(0).type());
        assertEquals(WordDocumentCommandParser.CommandType.FONT_FAMILY, commands.get(1).type());
        assertEquals(WordDocumentCommandParser.CommandType.FORMAT_CENTER, commands.get(2).type());
        assertEquals(WordDocumentCommandParser.CommandType.REPLACE, commands.get(3).type());
        assertEquals(WordDocumentCommandParser.CommandType.BEAUTIFY, commands.get(4).type());
    }

    @Test
    void parsesConversationalAndScopedCommands() {
        var commands = WordDocumentCommandParser.parseMany(
            "把标题字号调到22，正文字体宋体，正文两端对齐；添加二级标题：风险说明");

        assertEquals(4, commands.size());
        assertEquals(WordDocumentCommandParser.CommandType.TITLE_FONT_SIZE, commands.get(0).type());
        assertEquals("22", commands.get(0).firstValue());
        assertEquals(WordDocumentCommandParser.CommandType.BODY_FONT_FAMILY, commands.get(1).type());
        assertEquals("宋体", commands.get(1).firstValue());
        assertEquals(WordDocumentCommandParser.CommandType.BODY_ALIGN_BOTH, commands.get(2).type());
        assertEquals(WordDocumentCommandParser.CommandType.ADD_HEADING2, commands.get(3).type());
    }

    @Test
    void parsesScreenshotStyleConversationalRequest() {
        var commands = WordDocumentCommandParser.parseMany(
            "帮我把正文改成宋体，标题放大到24号，正文两端对齐，再加一段：这是口语化测试内容");

        assertEquals(4, commands.size());
        assertEquals(WordDocumentCommandParser.CommandType.BODY_FONT_FAMILY, commands.get(0).type());
        assertEquals("宋体", commands.get(0).firstValue());
        assertEquals(WordDocumentCommandParser.CommandType.TITLE_FONT_SIZE, commands.get(1).type());
        assertEquals("24", commands.get(1).firstValue());
        assertEquals(WordDocumentCommandParser.CommandType.BODY_ALIGN_BOTH, commands.get(2).type());
        assertEquals(WordDocumentCommandParser.CommandType.ADD_PARAGRAPH, commands.get(3).type());
        assertEquals("这是口语化测试内容", commands.get(3).firstValue());
    }

    @Test
    void keepsPunctuationInsideAddedParagraphContent() {
        var commands = WordDocumentCommandParser.parseMany(
            "添加段落：继续完善微信 Agent 的 Word 文档编辑能力，重点验证批量命令、口语化命令和格式作用范围。");

        assertEquals(1, commands.size());
        assertEquals(WordDocumentCommandParser.CommandType.ADD_PARAGRAPH, commands.get(0).type());
        assertTrue(commands.get(0).firstValue().contains("重点验证批量命令"));
    }

    @Test
    void extractsOnlyExplicitNextFileInstruction() {
        assertEquals(
            "美化排版",
            WordDocumentCommandParser.extractPendingFileInstruction(
                "等下发个文档，帮我美化排版"));
        assertEquals(
            "正文改成宋体，标题放大到24号",
            WordDocumentCommandParser.extractPendingFileInstruction(
                "我马上发 Word：正文改成宋体，标题放大到24号"));
        assertEquals(
            "标题居中",
            WordDocumentCommandParser.extractPendingFileInstruction(
                "接下来发的文档帮我标题居中"));
    }

    @Test
    void rejectsOrdinaryOrOldWordCommandsAsPendingContext() {
        assertNull(WordDocumentCommandParser.extractPendingFileInstruction("美化排版"));
        assertNull(WordDocumentCommandParser.extractPendingFileInstruction(
            "帮我把正文改成宋体，标题放大到24号"));
        assertNull(WordDocumentCommandParser.extractPendingFileInstruction(
            "我们聊一下 Word 文档怎么排版"));
        assertFalse(WordDocumentCommandParser.looksLikeWordDocumentCommandBatch("普通聊天"));
    }
}
