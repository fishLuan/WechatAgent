package com.clawbot.wechatbot.service.agent.reference;

/** $ref 解析的数量、深度、路径和最终输入大小限制。 */
public record ReferencePolicy(
    int maxReferencesPerTask,
    int maxDepth,
    int maxPathLength,
    int maxResolvedInputChars
) {
    public ReferencePolicy {
        if (maxReferencesPerTask < 1 || maxDepth < 1
            || maxPathLength < 16 || maxResolvedInputChars < 256) {
            throw new IllegalArgumentException("$ref 限制配置无效");
        }
    }

    public static ReferencePolicy defaults() {
        return new ReferencePolicy(20, 10, 300, 16000);
    }
}
