package com.clawbot.wechatbot.feature.document.application;

import com.clawbot.wechatbot.feature.document.messaging.WordDocumentCommandParser.CommandType;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordDocumentNaturalLanguagePlannerTests {
    private final WordDocumentNaturalLanguagePlanner planner =
        new WordDocumentNaturalLanguagePlanner(new DeepSeekClient(
            "", "test", "http://localhost", 0, 1000, 1, 1));

    @Test
    void parsesWhitelistedNaturalLanguagePlan() {
        var result = planner.parseModelContent("""
            ```json
            {"status":"READY","clarification":"","commands":[
              {"type":"TITLE_FONT_SIZE","first":"24","second":""},
              {"type":"BODY_FONT_FAMILY","first":"宋体","second":""},
              {"type":"REPLACE","first":"旧公司","second":"ClawBot团队"}
            ]}
            ```
            """);

        assertEquals(WordDocumentNaturalLanguagePlanner.Status.READY, result.status());
        assertEquals(3, result.commands().size());
        assertEquals(CommandType.TITLE_FONT_SIZE, result.commands().get(0).type());
        assertEquals("宋体", result.commands().get(1).firstValue());
        assertEquals("ClawBot团队", result.commands().get(2).secondValue());
    }

    @Test
    void returnsClarificationInsteadOfGuessing() {
        var result = planner.parseModelContent("""
            {"status":"CLARIFY","clarification":"你希望哪一段加粗？","commands":[]}
            """);

        assertEquals(WordDocumentNaturalLanguagePlanner.Status.CLARIFY, result.status());
        assertEquals("你希望哪一段加粗？", result.message());
    }

    @Test
    void rejectsUnknownOperation() {
        var result = planner.parseModelContent("""
            {"status":"READY","commands":[
              {"type":"RUN_MACRO","first":"x","second":""}
            ]}
            """);

        assertEquals(WordDocumentNaturalLanguagePlanner.Status.INVALID, result.status());
        assertTrue(result.commands().isEmpty());
    }

    @Test
    void parsesParagraphScopedOperations() {
        var result = planner.parseModelContent("""
            {"status":"READY","commands":[
              {"type":"PARAGRAPH_BOLD","first":"2","second":""},
              {"type":"MATCHING_PARAGRAPH_ALIGN","first":"风险说明","second":"CENTER"}
            ]}
            """);

        assertEquals(WordDocumentNaturalLanguagePlanner.Status.READY, result.status());
        assertEquals(CommandType.PARAGRAPH_BOLD, result.commands().get(0).type());
        assertEquals("2", result.commands().get(0).firstValue());
        assertEquals(CommandType.MATCHING_PARAGRAPH_ALIGN, result.commands().get(1).type());
    }
}
