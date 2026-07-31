package com.clawbot.wechatbot.skills;

/** Java implementation selected by a skill definition's executor field. */
public interface SkillExecutor {
    String executorName();

    SkillResult execute(SkillDefinition definition, SkillRequest request)
        throws Exception;
}
