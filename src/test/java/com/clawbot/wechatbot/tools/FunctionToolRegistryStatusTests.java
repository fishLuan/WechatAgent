package com.clawbot.wechatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionToolRegistryStatusTests {

    @Test
    void recognizesStatusOkAndErrorContracts() {
        ObjectMapper mapper = new ObjectMapper();
        FunctionToolRegistry successRegistry = new FunctionToolRegistry(
            mapper, List.of(tool(mapper, "{\"status\":\"ok\",\"result\":\"10:00\"}")));
        FunctionToolRegistry failureRegistry = new FunctionToolRegistry(
            mapper, List.of(tool(mapper, "{\"status\":\"error\",\"message\":\"bad timezone\"}")));

        assertTrue(successRegistry.executeWithOutcome("test_tool", "{}").success());
        assertFalse(failureRegistry.executeWithOutcome("test_tool", "{}").success());
    }

    private FunctionTool tool(ObjectMapper mapper, String result) {
        return new FunctionTool() {
            @Override public String name() { return "test_tool"; }
            @Override public JsonNode definition() {
                return mapper.createObjectNode();
            }
            @Override public String execute(JsonNode arguments) { return result; }
        };
    }
}
