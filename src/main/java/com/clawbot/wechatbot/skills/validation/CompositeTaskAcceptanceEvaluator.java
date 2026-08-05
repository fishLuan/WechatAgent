package com.clawbot.wechatbot.skills.validation;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.acceptance.TaskAcceptanceEvaluator;
import com.clawbot.wechatbot.service.agent.acceptance.TaskDecision;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;

/** Runs generic acceptance first, then validators declared by skill.yaml. */
public final class CompositeTaskAcceptanceEvaluator implements TaskAcceptanceEvaluator {
    private final TaskAcceptanceEvaluator generic;
    private final SkillCatalog skills;
    private final SkillResultValidatorRegistry registry;

    public CompositeTaskAcceptanceEvaluator(
        TaskAcceptanceEvaluator generic, SkillCatalog skills,
        SkillResultValidatorRegistry registry
    ) {
        this.generic = generic;
        this.skills = skills;
        this.registry = registry;
    }

    @Override
    public TaskEvaluation evaluate(
        AgentTask task, AgentTaskResult result,
        Map<String, AgentTaskResult> verifiedDependencies
    ) {
        TaskEvaluation base = generic.evaluate(task, result, verifiedDependencies);
        if (!base.passed() || task.type() != AgentTaskType.SKILL) return base;

        SkillDefinition definition = skills.definitions().stream()
            .filter(skill -> skill.name().equalsIgnoreCase(task.skillName()))
            .findFirst().orElse(null);
        if (definition == null) {
            return failure("SKILL_DEFINITION_NOT_FOUND",
                "找不到 Skill 定义：" + task.skillName());
        }
        SkillValidationDefinition validation = definition.validation();
        if (validation.mode() == SkillValidationMode.GENERIC) return base;

        List<SkillResultValidator> validators = registry.validatorsFor(
            validation.validator().isBlank() ? definition.name() : validation.validator());
        if (validators.isEmpty()) {
            return validation.mode() == SkillValidationMode.REQUIRED
                ? failure("REQUIRED_SKILL_VALIDATOR_MISSING",
                    "Skill " + definition.name() + " 缺少必需的专属校验器："
                        + validation.validator())
                : base;
        }

        SkillValidationContext context = new SkillValidationContext(
            task, result, base.verifiedOutput(), verifiedDependencies);
        for (SkillResultValidator validator : validators) {
            TaskEvaluation domain = validator.validate(context);
            if (!domain.passed()) return domain;
        }
        return base;
    }

    private TaskEvaluation failure(String code, String reason) {
        return new TaskEvaluation(TaskDecision.ABORT, code, reason,
            JsonNodeFactory.instance.objectNode(), List.of(),
            "注册对应校验器或调整 skill.yaml 的 validation.mode");
    }
}
