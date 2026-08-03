package com.clawbot.wechatbot.feature.bilibili.agent;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** B站领域内的有限规划、依赖执行和结果聚合循环。 */
@Component
public final class BilibiliSubAgent {
    private final BilibiliTaskPlanner planner;
    private final BilibiliCommandHandler commands;
    private final int maxTasks;
    private final int maxRounds;
    private final Duration timeout;

    public BilibiliSubAgent(
        BilibiliTaskPlanner planner,
        BilibiliCommandHandler commands,
        @Value("${bilibili.agent.max-tasks:5}") int maxTasks,
        @Value("${bilibili.agent.max-rounds:3}") int maxRounds,
        @Value("${bilibili.agent.timeout-seconds:30}") int timeoutSeconds
    ) {
        this.planner = planner;
        this.commands = commands;
        this.maxTasks = Math.max(1, maxTasks);
        this.maxRounds = Math.max(1, maxRounds);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public BilibiliAgentResult execute(String userId, String instruction) {
        if (userId == null || userId.isBlank()) {
            return BilibiliAgentResult.failure("B站子Agent缺少微信用户上下文");
        }
        if (instruction == null || instruction.isBlank()) {
            return BilibiliAgentResult.failure("B站子Agent缺少任务指令");
        }

        final List<BilibiliTask> plan;
        try {
            plan = planner.plan(instruction, maxTasks);
        } catch (Exception error) {
            return BilibiliAgentResult.failure(error.getMessage());
        }
        if (plan.isEmpty()) {
            return BilibiliAgentResult.failure("无法识别B站领域操作：" + instruction.trim());
        }

        BilibiliExecutionGuard guard = new BilibiliExecutionGuard(timeout, maxTasks);
        guard.validatePlan(plan.size());
        Map<String, BilibiliTaskResult> completed = new LinkedHashMap<>();
        Map<String, BilibiliTask> pending = new LinkedHashMap<>();
        plan.forEach(task -> pending.put(task.id(), task));

        for (int round = 0; round < maxRounds && !pending.isEmpty() && !guard.timedOut(); round++) {
            List<BilibiliTask> ready = pending.values().stream()
                .filter(task -> completed.keySet().containsAll(task.dependencies()))
                .sorted(Comparator.comparingInt(BilibiliTask::order))
                .toList();
            if (ready.isEmpty()) break;
            for (BilibiliTask task : ready) {
                BilibiliTaskResult result = executeTask(userId, task, completed, guard);
                completed.put(task.id(), result);
                pending.remove(task.id());
                if (guard.timedOut()) break;
            }
        }

        for (BilibiliTask task : pending.values()) {
            completed.put(task.id(), BilibiliTaskResult.failure(
                task, guard.timedOut() ? "B站子Agent执行超时" : "任务依赖未满足或超过循环次数"));
        }
        List<BilibiliTaskResult> results = plan.stream()
            .map(task -> completed.get(task.id()))
            .toList();
        boolean success = results.stream().allMatch(BilibiliTaskResult::success);
        return new BilibiliAgentResult(success, aggregate(results), results);
    }

    private BilibiliTaskResult executeTask(
        String userId,
        BilibiliTask task,
        Map<String, BilibiliTaskResult> completed,
        BilibiliExecutionGuard guard
    ) {
        for (String dependencyId : task.dependencies()) {
            BilibiliTaskResult dependency = completed.get(dependencyId);
            if (dependency == null || !dependency.success()) {
                return BilibiliTaskResult.failure(task, "前置B站任务失败，已停止执行");
            }
        }
        String blocked = guard.beforeExecute(task);
        if (blocked != null) return BilibiliTaskResult.failure(task, blocked);
        try {
            String reply = commands.handle(userId, task.instruction());
            if (reply == null || reply.isBlank()
                || reply.startsWith("[UNHANDLED-BILIBILI-UNKNOWN]")) {
                return BilibiliTaskResult.failure(task, "无法执行B站任务：" + task.instruction());
            }
            if (reply.startsWith("❌")) return BilibiliTaskResult.failure(task, reply);
            return BilibiliTaskResult.success(task, reply);
        } catch (Exception error) {
            String message = error.getMessage();
            return BilibiliTaskResult.failure(
                task, message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
        }
    }

    private String aggregate(List<BilibiliTaskResult> results) {
        if (results.size() == 1) return results.get(0).text();
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            BilibiliTaskResult result = results.get(index);
            parts.add((index + 1) + ". 【" + label(result.task().instruction()) + "】\n"
                + result.text());
        }
        return String.join("\n\n", parts);
    }

    private String label(String instruction) {
        String value = instruction.replaceAll("\\s+", " ").trim();
        return value.length() <= 30 ? value : value.substring(0, 30) + "…";
    }
}
