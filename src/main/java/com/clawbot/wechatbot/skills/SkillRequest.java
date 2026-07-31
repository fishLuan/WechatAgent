package com.clawbot.wechatbot.skills;

/** Skill执行所需的用户身份、任务指令和只读上下文。 */
public record SkillRequest(
    String userId,
    String instruction,
    String history,
    String supportingContext,
    String dependencyText
) {
    public SkillRequest {
        userId = safe(userId);
        instruction = safe(instruction);
        history = safe(history);
        supportingContext = safe(supportingContext);
        dependencyText = safe(dependencyText);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
