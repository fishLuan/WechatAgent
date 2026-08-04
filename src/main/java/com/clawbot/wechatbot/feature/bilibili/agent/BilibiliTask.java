package com.clawbot.wechatbot.feature.bilibili.agent;

import java.util.List;

/** 子Agent内部可执行、可声明依赖的领域任务。 */
public record BilibiliTask(
    String id,
    int order,
    BilibiliTaskType type,
    String instruction,
    List<String> dependencies
) {
    public BilibiliTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("任务ID不能为空");
        if (order < 0) throw new IllegalArgumentException("任务顺序不能为负数");
        if (type == null) throw new IllegalArgumentException("任务类型不能为空");
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("任务指令不能为空");
        }
        instruction = instruction.trim();
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
