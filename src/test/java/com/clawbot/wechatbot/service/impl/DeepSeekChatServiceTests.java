package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeepSeekChatServiceTests {

    @Test
    void feedsToolObservationBackIntoTheInnerLoop() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        FakeDeepSeekClient client = new FakeDeepSeekClient();
        client.enqueue(toolCallResponse(client.mapper()));
        client.enqueue(textResponse(client.mapper(), "杭州今天晴，25℃。"));
        FunctionTool weather = new FunctionTool() {
            @Override
            public String name() {
                return "get_weather";
            }

            @Override
            public JsonNode definition() {
                ObjectNode function = client.mapper().createObjectNode();
                function.put("name", name());
                ObjectNode definition = client.mapper().createObjectNode();
                definition.put("type", "function");
                definition.set("function", function);
                return definition;
            }

            @Override
            public String execute(JsonNode arguments) {
                executions.incrementAndGet();
                return "{\"success\":true,\"weather\":\"晴\",\"temperature\":\"25\"}";
            }
        };
        DeepSeekChatService service = new DeepSeekChatService(
            client,
            new FunctionToolRegistry(client.mapper(), List.of(weather)),
            "测试系统提示词",
            3);

        String answer = service.chat("杭州天气", "");

        assertEquals("杭州今天晴，25℃。", answer);
        assertEquals(1, executions.get());
        assertEquals(2, client.calls());
        JsonNode toolMessage = null;
        for (JsonNode message : client.secondRoundMessages()) {
            if ("tool".equals(message.path("role").asText())) {
                toolMessage = message;
                break;
            }
        }
        assertNotNull(toolMessage);
        assertEquals(
            "晴",
            client.mapper().readTree(toolMessage.path("content").asText())
                .path("weather").asText());
    }

    private JsonNode toolCallResponse(ObjectMapper mapper) {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", "get_weather");
        function.put("arguments", "{\"city\":\"杭州\"}");
        ObjectNode call = mapper.createObjectNode();
        call.put("id", "call-weather");
        call.put("type", "function");
        call.set("function", function);
        ArrayNode calls = mapper.createArrayNode().add(call);
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", "");
        message.set("tool_calls", calls);
        return response(mapper, message);
    }

    private JsonNode textResponse(ObjectMapper mapper, String text) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", text);
        return response(mapper, message);
    }

    private JsonNode response(ObjectMapper mapper, ObjectNode message) {
        ObjectNode choice = mapper.createObjectNode();
        choice.set("message", message);
        ObjectNode root = mapper.createObjectNode();
        root.set("choices", mapper.createArrayNode().add(choice));
        return root;
    }

    private static final class FakeDeepSeekClient extends DeepSeekClient {
        private final Queue<JsonNode> responses = new ArrayDeque<>();
        private int calls;
        private ArrayNode secondRoundMessages;

        private FakeDeepSeekClient() {
            super("test-key", "test-model", "https://example.invalid", 0, 100, 1, 1);
        }

        private void enqueue(JsonNode response) {
            responses.add(response);
        }

        private int calls() {
            return calls;
        }

        private ArrayNode secondRoundMessages() {
            return secondRoundMessages;
        }

        @Override
        public JsonNode chat(
            ArrayNode messages, ArrayNode tools, double requestTemperature
        ) {
            calls++;
            if (calls == 2) secondRoundMessages = messages.deepCopy();
            return responses.remove();
        }
    }
}
