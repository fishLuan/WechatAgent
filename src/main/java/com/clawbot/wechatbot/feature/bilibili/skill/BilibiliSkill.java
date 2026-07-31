package com.clawbot.wechatbot.feature.bilibili.skill;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

/** Existing Bilibili workflow exposed through the generic skill executor SPI. */
@Component
public final class BilibiliSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "bilibili";
    private final BilibiliCommandHandler commands;

    public BilibiliSkill(BilibiliCommandHandler commands) {
        this.commands = commands;
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
        String reply = commands.handle(request.userId(), request.instruction());
        if (reply == null || reply.isBlank()
            || reply.startsWith("[UNHANDLED-BILIBILI-UNKNOWN]")) {
            return SkillResult.failure(
                "Unable to recognize Bilibili operation: " + request.instruction());
        }
        if (reply.startsWith("❌")) return SkillResult.failure(reply);
        return SkillResult.success(reply);
    }
}
