package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 任务规划器输出的结构化任务。 */
public record AgentTask(
    String id,
    int order,
    AgentTaskType type,
    String instruction,
    List<String> dependencies
) {
    public AgentTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("任务 ID 不能为空");
        if (order < 0) throw new IllegalArgumentException("任务顺序不能小于 0");
        if (type == null) throw new IllegalArgumentException("任务类型不能为空");
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空");
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public static AgentTask chat(String instruction) {
        return new AgentTask("task-1", 0, AgentTaskType.CHAT_TOOL, instruction, List.of());
    }
}
