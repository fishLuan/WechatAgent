package com.clawbot.wechatbot.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

/** A structured task emitted by the outer-loop planner. */
public record AgentTask(
    String id,
    int order,
    AgentTaskType type,
    String skillName,
    String instruction,
    JsonNode input,
    JsonNode expectedOutput,
    List<AcceptanceCriterion> acceptanceCriteria,
    List<String> dependencies
) {
    public AgentTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Task id is required");
        if (order < 0) throw new IllegalArgumentException("Task order cannot be negative");
        if (type == null) throw new IllegalArgumentException("Task type is required");
        skillName = skillName == null ? "" : skillName.trim();
        if (type == AgentTaskType.SKILL && skillName.isBlank()) {
            throw new IllegalArgumentException("SKILL task requires skillName");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Task instruction is required");
        }
        input = input == null
            ? JsonNodeFactory.instance.objectNode() : input.deepCopy();
        if (!input.isObject()) {
            throw new IllegalArgumentException("Task input must be a JSON object");
        }
        expectedOutput = expectedOutput == null
            ? JsonNodeFactory.instance.objectNode() : expectedOutput.deepCopy();
        if (!expectedOutput.isObject()) {
            throw new IllegalArgumentException("Task expectedOutput must be a JSON object");
        }
        acceptanceCriteria = acceptanceCriteria == null
            ? List.of() : List.copyOf(acceptanceCriteria);
        if (acceptanceCriteria.size() > 16) {
            throw new IllegalArgumentException("Task acceptance criteria cannot exceed 16");
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** 兼容旧调用方的构造方法。 */
    public AgentTask(
        String id,
        int order,
        AgentTaskType type,
        String skillName,
        String instruction,
        List<String> dependencies
    ) {
        this(id, order, type, skillName, instruction,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            List.of(), dependencies);
    }

    public AgentTask(
        String id,
        int order,
        AgentTaskType type,
        String instruction,
        List<String> dependencies
    ) {
        this(id, order, type, "", instruction, dependencies);
    }

    public static AgentTask chat(String instruction) {
        return new AgentTask(
            "task-1", 0, AgentTaskType.CHAT_TOOL, "", instruction, List.of());
    }

    @Override
    public JsonNode input() {
        return input.deepCopy();
    }

    @Override
    public JsonNode expectedOutput() {
        return expectedOutput.deepCopy();
    }
}
