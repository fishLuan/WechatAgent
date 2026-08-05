package com.clawbot.wechatbot.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserFacingResultFormatterTests {

    private final UserFacingResultFormatter formatter =
        new UserFacingResultFormatter(new ObjectMapper());

    @Test
    void extractsWeatherTextFromStructuredResult() {
        assertEquals(
            "南京今天小雨，27℃~35℃。",
            formatter.format("{\"weather_text\":\"南京今天小雨，27℃~35℃。\"}"));
    }

    @Test
    void extractsPoemWithoutDisplayingJsonWrapper() {
        assertEquals(
            "山中明月照，石上清泉流。",
            formatter.format("{\"poem\":\"山中明月照，石上清泉流。\"}"));
    }

    @Test
    void keepsPlainTextUnchanged() {
        assertEquals("文档已生成：document.pdf", formatter.format("文档已生成：document.pdf"));
    }

    @Test
    void rendersUnknownObjectAsReadableFields() {
        assertEquals("city：杭州\ntemperature：35", formatter.format(
            "{\"city\":\"杭州\",\"temperature\":35}"));
    }
}
