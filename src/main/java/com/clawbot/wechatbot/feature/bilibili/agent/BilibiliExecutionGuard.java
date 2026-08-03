package com.clawbot.wechatbot.feature.bilibili.agent;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** B站子Agent的时间、数量和重复执行防护。 */
public final class BilibiliExecutionGuard {
    private final long deadlineNanos;
    private final int maxTasks;
    private final Set<String> executed = new HashSet<>();

    public BilibiliExecutionGuard(Duration timeout, int maxTasks) {
        Duration safeTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
            ? Duration.ofSeconds(30) : timeout;
        this.deadlineNanos = System.nanoTime() + safeTimeout.toNanos();
        this.maxTasks = Math.max(1, maxTasks);
    }

    public void validatePlan(int taskCount) {
        if (taskCount > maxTasks) {
            throw new IllegalArgumentException("B站子任务超过上限，当前最多处理 " + maxTasks + " 项");
        }
    }

    public String beforeExecute(BilibiliTask task) {
        if (System.nanoTime() >= deadlineNanos) return "B站子Agent执行超时";
        String signature = task.type() + ":" + task.instruction()
            .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (!executed.add(signature)) return "阻止重复执行相同B站任务";
        return null;
    }

    public boolean timedOut() {
        return System.nanoTime() >= deadlineNanos;
    }
}
