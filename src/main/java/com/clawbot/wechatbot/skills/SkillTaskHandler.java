package com.clawbot.wechatbot.skills;

import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskContext;
import com.clawbot.wechatbot.service.agent.AgentTaskHandler;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import org.springframework.stereotype.Component;

/** Generic outer-loop handler for all dynamically registered domain skills. */
@Component
public final class SkillTaskHandler implements AgentTaskHandler {
    private final SkillManager skills;
    private final AgentRequestContextHolder requestContextHolder;

    public SkillTaskHandler(SkillManager skills, AgentRequestContextHolder requestContextHolder) {
        this.skills = skills;
        this.requestContextHolder = requestContextHolder;
    }

    @Override
    public boolean supports(AgentTaskType type) {
        return type == AgentTaskType.SKILL;
    }

    @Override
    public AgentTaskResult execute(AgentTask task, AgentTaskContext context)
        throws Exception {
        SkillResult result = skills.execute(task.skillName(), new SkillRequest(
            requestContextHolder.currentUserId(), task.instruction(),
            context.history(), context.supportingContext(), context.dependencyText()));
        return result.success()
            ? (result.hasMultipleTexts()
                ? AgentTaskResult.successMulti(task, result.texts())
                : AgentTaskResult.success(task, result.text(), result.attachments()))
            : AgentTaskResult.failure(task, result.text());
    }
}
