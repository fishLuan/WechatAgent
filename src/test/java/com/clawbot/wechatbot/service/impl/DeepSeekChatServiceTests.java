package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.agent.guard.AgentExecutionGuard;
import com.clawbot.wechatbot.service.agent.guard.AgentGuardPolicy;
import com.clawbot.wechatbot.service.longform.LongFormGenerationPolicy;
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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            3,
            guard(client.mapper()));

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

    @Test
    void doesNotFeedMismatchedToolResultIntoFollowingSteps() throws Exception {
        FakeDeepSeekClient client = new FakeDeepSeekClient();
        client.enqueue(toolCallResponse(client.mapper()));
        client.enqueue(textResponse(client.mapper(), "天气结果不可信，已停止生成行程。"));
        FunctionTool weather = new FunctionTool() {
            @Override public String name() { return "get_weather"; }
            @Override public JsonNode definition() {
                ObjectNode function = client.mapper().createObjectNode();
                function.put("name", name());
                ObjectNode definition = client.mapper().createObjectNode();
                definition.put("type", "function");
                definition.set("function", function);
                return definition;
            }
            @Override public String execute(JsonNode arguments) {
                return "{\"success\":true,\"query_city\":\"上海\","
                    + "\"weather\":\"晴\"}";
            }
        };
        DeepSeekChatService service = new DeepSeekChatService(
            client, new FunctionToolRegistry(client.mapper(), List.of(weather)),
            "测试系统提示词", 3, guard(client.mapper()));

        String answer = service.chat("查询杭州天气并生成行程", "");

        assertEquals("天气结果不可信，已停止生成行程。", answer);
        String messages = client.messagesAtCall(2).toString();
        assertTrue(messages.contains("TOOL_RESULT_ARGUMENT_MISMATCH"));
        assertTrue(messages.contains("discarded_untrusted_result"));
        assertTrue(!messages.contains("上海"));
    }

    @Test
    void blocksRepeatedSuccessfulToolCallAndForcesFinalResponse() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        FakeDeepSeekClient client = new FakeDeepSeekClient();
        client.enqueue(toolCallResponse(client.mapper()));
        client.enqueue(toolCallResponse(client.mapper()));
        client.enqueue(textResponse(client.mapper(), "请直接使用第一次天气结果。"));
        FunctionTool weather = tool(client.mapper(), executions, true);
        DeepSeekChatService service = new DeepSeekChatService(
            client,
            new FunctionToolRegistry(client.mapper(), List.of(weather)),
            "测试系统提示词",
            3,
            guard(client.mapper()));

        String answer = service.chat("杭州天气", "");

        assertEquals("请直接使用第一次天气结果。", answer);
        assertEquals(1, executions.get());
        assertEquals(3, client.calls());
        assertTrue(client.toolsAtCall(3).isEmpty());
        assertTrue(client.messagesAtCall(3).toString().contains("DUPLICATE_TOOL_CALL"));
    }

    @Test
    void opensCircuitAfterTwoFailuresAndRemovesToolDefinition() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        FakeDeepSeekClient client = new FakeDeepSeekClient();
        client.enqueue(toolCallResponse(client.mapper()));
        client.enqueue(toolCallResponse(client.mapper()));
        client.enqueue(textResponse(client.mapper(), "天气工具暂时不可用。"));
        FunctionTool weather = tool(client.mapper(), executions, false);
        DeepSeekChatService service = new DeepSeekChatService(
            client,
            new FunctionToolRegistry(client.mapper(), List.of(weather)),
            "测试系统提示词",
            3,
            guard(client.mapper()));

        String answer = service.chat("杭州天气", "");

        assertEquals("天气工具暂时不可用。", answer);
        assertEquals(2, executions.get());
        assertTrue(client.toolsAtCall(3).isEmpty());
    }

    @Test
    void continuesUntilExplicitCharacterTargetIsReached() throws Exception {
        FakeDeepSeekClient client = new FakeDeepSeekClient();
        client.enqueue(textResponse(
            client.mapper(), "小船离开港口，驶入夜色。", "stop"));
        client.enqueue(textResponse(
            client.mapper(), "月光铺在海面上，少年终于找到了回家的方向。", "stop"));
        DeepSeekChatService service = serviceWithLongForm(
            client, new LongFormGenerationPolicy(true, 20, 100, 10, 3, 120));

        String answer = service.chat("请写一篇30字左右的小故事", "");

        assertEquals(2, client.calls());
        assertTrue(answer.contains("小船离开港口"));
        assertTrue(answer.contains("少年终于找到了回家的方向"));
        assertTrue(client.messagesAtCall(2).toString().contains("原要求约30字"));
        assertTrue(client.toolsAtCall(2).isEmpty());
    }

    @Test
    void continuesWhenProviderReportsLengthTruncation() throws Exception {
        FakeDeepSeekClient client = new FakeDeepSeekClient();
        client.enqueue(textResponse(
            client.mapper(), "故事讲到一半，门忽然打开", "length"));
        client.enqueue(textResponse(
            client.mapper(), "，失踪多年的父亲走了进来。", "stop"));
        DeepSeekChatService service = serviceWithLongForm(
            client, new LongFormGenerationPolicy(true, 1200, 3000, 10, 2, 4000));

        String answer = service.chat("讲个故事", "");

        assertEquals(2, client.calls());
        assertTrue(answer.contains("门忽然打开，失踪多年的父亲走了进来。"));
    }

    private DeepSeekChatService serviceWithLongForm(
        FakeDeepSeekClient client, LongFormGenerationPolicy policy
    ) {
        return new DeepSeekChatService(
            client,
            new FunctionToolRegistry(client.mapper(), List.of()),
            "测试系统提示词",
            3,
            guard(client.mapper()),
            policy);
    }

    private FunctionTool tool(
        ObjectMapper mapper, AtomicInteger executions, boolean success
    ) {
        return new FunctionTool() {
            @Override
            public String name() {
                return "get_weather";
            }

            @Override
            public JsonNode definition() {
                ObjectNode function = mapper.createObjectNode();
                function.put("name", name());
                ObjectNode definition = mapper.createObjectNode();
                definition.put("type", "function");
                definition.set("function", function);
                return definition;
            }

            @Override
            public String execute(JsonNode arguments) {
                executions.incrementAndGet();
                return success
                    ? "{\"success\":true,\"weather\":\"晴\"}"
                    : "{\"success\":false,\"error\":\"模拟失败\"}";
            }
        };
    }

    private AgentExecutionGuard guard(ObjectMapper mapper) {
        return new AgentExecutionGuard(
            new AgentGuardPolicy(
                2, 4, 8, 2, 8000, 16000, Duration.ofSeconds(90)),
            mapper);
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
        return textResponse(mapper, text, "");
    }

    private JsonNode textResponse(
        ObjectMapper mapper, String text, String finishReason
    ) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", text);
        ObjectNode response = (ObjectNode) response(mapper, message);
        if (!finishReason.isBlank()) {
            ((ObjectNode) response.path("choices").path(0))
                .put("finish_reason", finishReason);
        }
        return response;
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
        private final List<ArrayNode> toolsByCall = new java.util.ArrayList<>();
        private final List<ArrayNode> messagesByCall = new java.util.ArrayList<>();
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

        private ArrayNode toolsAtCall(int oneBasedCall) {
            return toolsByCall.get(oneBasedCall - 1);
        }

        private ArrayNode messagesAtCall(int oneBasedCall) {
            return messagesByCall.get(oneBasedCall - 1);
        }

        @Override
        public JsonNode chat(
            ArrayNode messages, ArrayNode tools, double requestTemperature
        ) {
            calls++;
            toolsByCall.add(tools.deepCopy());
            messagesByCall.add(messages.deepCopy());
            if (calls == 2) secondRoundMessages = messages.deepCopy();
            return responses.remove();
        }
    }
}
