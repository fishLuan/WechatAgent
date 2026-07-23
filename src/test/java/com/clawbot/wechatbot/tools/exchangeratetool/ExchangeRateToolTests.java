package com.clawbot.wechatbot.tools.exchangeratetool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeRateToolTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesFunctionCallingDefinition() {
        ExchangeRateTool tool = new ExchangeRateTool("test-key");

        JsonNode definition = tool.definition();

        assertEquals("function", definition.path("type").asText());
        assertEquals("convert_currency", definition.path("function").path("name").asText());
        JsonNode required = definition.path("function").path("parameters").path("required");
        assertFalse(required.toString().contains("amount"));
        assertTrue(required.toString().contains("from_currency"));
        assertTrue(required.toString().contains("to_currency"));
    }

    @Test
    void normalizesCommonChineseCurrencyNames() {
        assertEquals("CNY", ExchangeRateTool.normalizeCurrency("人民币"));
        assertEquals("USD", ExchangeRateTool.normalizeCurrency("美元"));
        assertEquals("EUR", ExchangeRateTool.normalizeCurrency(" eur "));
        assertEquals("", ExchangeRateTool.normalizeCurrency("不存在的币种"));
    }

    @Test
    void reportsMissingApiKeyWithoutCallingNetwork() throws Exception {
        ExchangeRateTool tool = new ExchangeRateTool("");

        String output = tool.execute(mapper.readTree(
            "{\"amount\":100,\"from_currency\":\"USD\",\"to_currency\":\"CNY\"}"));

        JsonNode result = mapper.readTree(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("JUHE_EXCHANGE_API_KEY"));
    }

    @Test
    void sameCurrencyDoesNotCallNetwork() throws Exception {
        ExchangeRateTool tool = new ExchangeRateTool("test-key");

        String output = tool.execute(mapper.readTree(
            "{\"amount\":12.345,\"from_currency\":\"人民币\",\"to_currency\":\"CNY\",\"precision\":2}"));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.path("success").asBoolean());
        assertEquals("12.35", result.path("target_amount").asText());
        assertEquals("1", result.path("exchange_rate").asText());
    }

    @Test
    void defaultsAmountToOneWhenOnlyQueryingRate() throws Exception {
        ExchangeRateTool tool = new ExchangeRateTool("test-key");

        String output = tool.execute(mapper.readTree(
            "{\"from_currency\":\"人民币\",\"to_currency\":\"CNY\"}"));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.path("success").asBoolean());
        assertEquals("1", result.path("source_amount").asText());
        assertEquals("1.00", result.path("target_amount").asText());
    }
}
