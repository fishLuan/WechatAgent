package com.clawbot.wechatbot.service.agent.replan;

import java.time.Duration;

/** 外层自动重试和局部重规划的统一预算。 */
public record AgentReplanPolicy(
    boolean enabled,
    int maxReplans,
    int maxRetriesPerTask,
    int maxTotalTaskExecutions,
    int maxTotalTasks,
    Duration timeout
) {
    public AgentReplanPolicy {
        if (maxReplans < 0) throw new IllegalArgumentException("最大重规划次数不能小于 0");
        if (maxRetriesPerTask < 0) throw new IllegalArgumentException("任务重试次数不能小于 0");
        if (maxTotalTaskExecutions < 1) {
            throw new IllegalArgumentException("任务总执行次数必须大于 0");
        }
        if (maxTotalTasks < 1) throw new IllegalArgumentException("任务总数必须大于 0");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("重规划超时必须大于 0");
        }
    }

    public static AgentReplanPolicy disabled() {
        return new AgentReplanPolicy(
            false, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Duration.ofSeconds(1));
    }
}
