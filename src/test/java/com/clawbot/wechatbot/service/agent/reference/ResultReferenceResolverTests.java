package com.clawbot.wechatbot.service.agent.reference;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultReferenceResolverTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultReferenceResolver resolver = new ResultReferenceResolver(
        mapper, ReferencePolicy.defaults());

    @Test
    void resolvesNestedObjectAndArrayReferencesFromVerifiedOutput() {
        AgentTask source = task("search", mapper.createObjectNode(), List.of());
        ObjectNode input = mapper.createObjectNode();
        input.putObject("subscription").putObject("seasonId")
            .put("$ref", "search.output.items[0].seasonId");
        input.putObject("title").put(
            "$ref", "search.output.items[0].title");
        AgentTask target = task("subscribe", input, List.of("search"));
        AgentExecutionState state = verifiedState(source, target);

        ResolvedTaskInput resolved = resolver.resolve(target, state);

        assertEquals(
            "ss123", resolved.input().path("subscription").path("seasonId").asText());
        assertEquals("火影忍者", resolved.input().path("title").asText());
        assertEquals(2, resolved.lineage().size());
        DataLineageRecord record = resolved.lineage().get(0);
        assertEquals("search", record.sourceTaskId());
        assertEquals("subscribe", record.targetTaskId());
        assertTrue(record.valueHash().startsWith("sha256:"));
        assertFalse(record.toString().contains("ss123"));
    }

    @Test
    void rejectsReferenceToUndeclaredDependency() {
        AgentTask source = task("search", mapper.createObjectNode(), List.of());
        ObjectNode input = mapper.createObjectNode();
        input.putObject("id").put("$ref", "search.output.items[0].seasonId");
        AgentTask target = task("subscribe", input, List.of());
        AgentExecutionState state = verifiedState(source, target);

        ReferenceResolutionException error = assertThrows(
            ReferenceResolutionException.class,
            () -> resolver.resolve(target, state));

        assertEquals("REF_DEPENDENCY_NOT_DECLARED", error.code());
    }

    @Test
    void rejectsReferenceToUnverifiedTask() {
        AgentTask source = task("search", mapper.createObjectNode(), List.of());
        ObjectNode input = mapper.createObjectNode();
        input.putObject("id").put("$ref", "search.output.items[0].seasonId");
        AgentTask target = task("subscribe", input, List.of("search"));
        AgentExecutionState state = new AgentExecutionState(
            "请求", List.of(source, target));

        ReferenceResolutionException error = assertThrows(
            ReferenceResolutionException.class,
            () -> resolver.resolve(target, state));

        assertEquals("REF_SOURCE_NOT_VERIFIED", error.code());
    }

    @Test
    void rejectsMissingOutputPath() {
        AgentTask source = task("search", mapper.createObjectNode(), List.of());
        ObjectNode input = mapper.createObjectNode();
        input.putObject("id").put("$ref", "search.output.items[2].seasonId");
        AgentTask target = task("subscribe", input, List.of("search"));
        AgentExecutionState state = verifiedState(source, target);

        ReferenceResolutionException error = assertThrows(
            ReferenceResolutionException.class,
            () -> resolver.resolve(target, state));

        assertEquals("REF_PATH_NOT_FOUND", error.code());
    }

    @Test
    void wrapsScalarRootReferenceAsObjectInput() {
        AgentTask source = task("search", mapper.createObjectNode(), List.of());
        ObjectNode input = mapper.createObjectNode();
        input.put("$ref", "search.output.weather_info");
        AgentTask target = task("voice", input, List.of("search"));
        AgentExecutionState state = new AgentExecutionState(
            "查询天气并语音回复", List.of(source, target));
        ObjectNode output = mapper.createObjectNode()
            .put("weather_info", "阜阳今天晴，气温30度");
        state.markRunning(source);
        state.recordResult(
            AgentTaskResult.success(source, output.toString(), List.of()),
            TaskEvaluation.pass(output));

        ResolvedTaskInput resolved = resolver.resolve(target, state);

        assertTrue(resolved.input().isObject());
        assertEquals("阜阳今天晴，气温30度",
            resolved.input().path("value").asText());
    }

    private AgentExecutionState verifiedState(AgentTask source, AgentTask target) {
        AgentExecutionState state = new AgentExecutionState(
            "请求", List.of(source, target));
        ObjectNode output = mapper.createObjectNode();
        output.putArray("items").addObject()
            .put("title", "火影忍者")
            .put("seasonId", "ss123");
        state.markRunning(source);
        state.recordResult(
            AgentTaskResult.success(source, output.toString(), List.of()),
            TaskEvaluation.pass(output));
        return state;
    }

    private AgentTask task(String id, ObjectNode input, List<String> dependencies) {
        return new AgentTask(
            id, "search".equals(id) ? 0 : 1, AgentTaskType.CHAT_TOOL,
            "", "任务 " + id, input, mapper.createObjectNode(),
            List.of(), dependencies);
    }
}
