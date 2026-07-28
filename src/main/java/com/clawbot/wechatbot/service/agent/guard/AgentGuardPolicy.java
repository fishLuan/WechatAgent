package com.clawbot.wechatbot.service.agent.guard;

import java.time.Duration;

/** Agent 内外循环的统一安全预算。 */
public record AgentGuardPolicy(
    int maxChatDepth,
    int maxToolCallsPerRound,
    int maxTotalToolCalls,
    int maxSameToolFailures,
    int maxToolResultChars,
    int maxTotalToolResultChars,
    Duration executionTimeout
) {
    public AgentGuardPolicy {
        if (maxChatDepth < 1) throw new IllegalArgumentException("chat 最大深度必须大于 0");
        if (maxToolCallsPerRound < 1) {
            throw new IllegalArgumentException("每轮工具调用数必须大于 0");
        }
        if (maxTotalToolCalls < maxToolCallsPerRound) {
            throw new IllegalArgumentException("工具调用总数不能小于每轮工具调用数");
        }
        if (maxSameToolFailures < 1) {
            throw new IllegalArgumentException("同一工具失败次数必须大于 0");
        }
        if (maxToolResultChars < 512) {
            throw new IllegalArgumentException("单个工具结果上限不能小于 512");
        }
        if (maxTotalToolResultChars < maxToolResultChars) {
            throw new IllegalArgumentException("累计工具结果上限不能小于单个结果上限");
        }
        if (executionTimeout == null
            || executionTimeout.isZero()
            || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("Agent 执行超时必须大于 0");
        }
    }
}
