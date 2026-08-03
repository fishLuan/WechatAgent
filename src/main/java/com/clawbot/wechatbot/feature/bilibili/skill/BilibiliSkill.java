package com.clawbot.wechatbot.feature.bilibili.skill;

import com.clawbot.wechatbot.feature.bilibili.agent.BilibiliAgentResult;
import com.clawbot.wechatbot.feature.bilibili.agent.BilibiliSubAgent;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

/** Existing Bilibili workflow exposed through the generic skill executor SPI. */
@Component
public final class BilibiliSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "bilibili";
    private final BilibiliSubAgent subAgent;

    public BilibiliSkill(BilibiliSubAgent subAgent) {
        this.subAgent = subAgent;
    }

    @Override
    public String executorName() {
        return EXECUTOR_NAME;
    }

    @Override
    public SkillResult execute(SkillDefinition definition, SkillRequest request) {
        if (request == null || request.userId().isBlank()) {
            return SkillResult.failure("Bilibili skill requires WeChat user context");
        }
        if (request.instruction().isBlank()) {
            return SkillResult.failure("Bilibili skill requires an instruction");
        }
        BilibiliAgentResult result = subAgent.execute(
            request.userId(), request.instruction());
        return result.success()
            ? SkillResult.success(result.text())
            : SkillResult.failure(result.text());
    }
}
