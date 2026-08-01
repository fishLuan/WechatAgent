package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** A structured task emitted by the outer-loop planner. */
public record AgentTask(
    String id,
    int order,
    AgentTaskType type,
    String skillName,
    String instruction,
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
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
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
}
