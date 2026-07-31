package com.clawbot.wechatbot.service.agent;

import java.util.List;

/** 一次任务规划的结果，显式区分正常计划与任务数量超限。 */
public record TaskPlan(
    List<AgentTask> tasks,
    int detectedTaskCount,
    int maxPlannedTasks,
    boolean limitExceeded
) {
    public TaskPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        detectedTaskCount = Math.max(detectedTaskCount, tasks.size());
        maxPlannedTasks = Math.max(1, maxPlannedTasks);
    }

    public static TaskPlan accepted(List<AgentTask> tasks) {
        List<AgentTask> safe = tasks == null ? List.of() : List.copyOf(tasks);
        return accepted(safe, Math.max(1, safe.size()));
    }

    public static TaskPlan accepted(
        List<AgentTask> tasks,
        int maxPlannedTasks
    ) {
        List<AgentTask> safe = tasks == null ? List.of() : List.copyOf(tasks);
        return new TaskPlan(
            safe, safe.size(), maxPlannedTasks, false);
    }

    public static TaskPlan limitExceeded(
        int detectedTaskCount,
        int maxPlannedTasks
    ) {
        return new TaskPlan(
            List.of(), detectedTaskCount, maxPlannedTasks, true);
    }

    public String userMessage() {
        if (!limitExceeded) return "";
        return "检测到你的消息包含 " + detectedTaskCount
            + " 项任务，当前一次最多处理 " + maxPlannedTasks
            + " 项。请拆成多条消息发送，或指定优先处理哪些任务。";
    }
}
