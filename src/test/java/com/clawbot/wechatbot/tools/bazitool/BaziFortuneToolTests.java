package com.clawbot.wechatbot.tools.bazitool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaziFortuneToolTests {
    private ObjectMapper mapper;
    private BaziFortuneTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        tool = new BaziFortuneTool(mapper);
    }

    @Test
    void calculatesKnownSolarFourPillars() throws Exception {
        JsonNode result = execute("""
            {
              "birth_date": "1986-05-29",
              "birth_time": "00:00",
              "calendar_type": "SOLAR",
              "fortune_year": 2026
            }
            """);

        assertTrue(result.path("success").asBoolean());
        assertEquals("丙寅", result.path("four_pillars").get(0).path("pillar").asText());
        assertEquals("癸巳", result.path("four_pillars").get(1).path("pillar").asText());
        assertEquals("癸酉", result.path("four_pillars").get(2).path("pillar").asText());
        assertEquals("子", result.path("four_pillars").get(3).path("branch").asText());
        assertEquals("丙午", result.path("fortune_year").path("pillar").asText());
        assertTrue(result.path("notice").asText().contains("文化娱乐参考"));
    }

    @Test
    void convertsEquivalentLunarDate() throws Exception {
        JsonNode result = execute("""
            {
              "birth_date": "1986-04-21",
              "birth_time": "00:00",
              "calendar_type": "LUNAR"
            }
            """);

        assertTrue(result.path("success").asBoolean());
        assertTrue(result.path("solar_datetime").asText().startsWith("1986-05-29"));
        assertEquals("癸酉", result.path("four_pillars").get(2).path("pillar").asText());
    }

    @Test
    void acceptsLunarDayThatIsNotAGregorianDate() throws Exception {
        JsonNode result = execute("""
            {
              "birth_date": "2020-02-30",
              "birth_time": "08:00",
              "calendar_type": "LUNAR"
            }
            """);

        assertTrue(result.path("success").asBoolean());
        assertTrue(result.path("solar_datetime").asText().startsWith("2020-03-23"));
    }

    @Test
    void rejectsUnsupportedTimezone() throws Exception {
        JsonNode result = execute("""
            {
              "birth_date": "2001-08-15",
              "birth_time": "14:30",
              "timezone": "America/New_York"
            }
            """);

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("东八区"));
    }

    @Test
    void rejectsLeapMonthForSolarDate() throws Exception {
        JsonNode result = execute("""
            {
              "birth_date": "2001-08-15",
              "birth_time": "14:30",
              "calendar_type": "SOLAR",
              "leap_month": true
            }
            """);

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("公历日期"));
    }

    private JsonNode execute(String rawArguments) throws Exception {
        return mapper.readTree(tool.execute(mapper.readTree(rawArguments)));
    }
}
