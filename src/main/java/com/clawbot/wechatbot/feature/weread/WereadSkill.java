package com.clawbot.wechatbot.feature.weread;

import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

/** 微信读书助手：通过官方 Agent 网关提供书架/统计/笔记/搜索/推荐能力。 */
@Component
public final class WereadSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "weread";
    private final WereadCommandHandler commands;
    private final WereadProperties properties;

    public WereadSkill(WereadCommandHandler commands, WereadProperties properties) {
        this.commands = commands;
        this.properties = properties;
    }

    @Override
    public String executorName() {
        return EXECUTOR_NAME;
    }

    @Override
    public SkillResult execute(SkillDefinition definition, SkillRequest request) {
        if (!properties.hasApiKey()) {
            return SkillResult.failure(
                "微信读书未配置：请设置环境变量 WEREAD_API_KEY（部署者在服务器环境变量配置后重启）");
        }
        if (request == null || request.userId().isBlank()) {
            return SkillResult.failure("Weread skill requires WeChat user context");
        }
        if (request.instruction().isBlank()) {
            return SkillResult.failure("Weread skill requires an instruction");
        }
        try {
            String reply = commands.handle(request.instruction());
            if (reply == null || reply.isBlank() || reply.startsWith("❌")) {
                return SkillResult.failure(reply == null ? "微信读书操作失败" : reply);
            }
            return SkillResult.success(reply);
        } catch (Exception error) {
            return SkillResult.failure("微信读书操作失败：" + safeMessage(error));
        }
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null
            ? error.getClass().getSimpleName()
            : error.getMessage();
    }
}
