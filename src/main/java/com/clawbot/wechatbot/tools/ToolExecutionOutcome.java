package com.clawbot.wechatbot.tools;

/** 工具注册表返回给 Agent 防护层的结构化执行结果。 */
public record ToolExecutionOutcome(
    String content,
    boolean success,
    boolean retryable,
    String code
) {
    public ToolExecutionOutcome {
        content = content == null ? "" : content;
        code = code == null ? "" : code;
    }

    public boolean hasUsableContent() {
        return success && !content.isBlank();
    }
}
