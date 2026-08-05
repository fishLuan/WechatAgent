package com.clawbot.wechatbot.skills.validation;

import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;

/** 可随 Skill 模块一起注册的业务结果校验器。 */
public interface SkillResultValidator {
    String validatorName();

    default int order() {
        return 100;
    }

    TaskEvaluation validate(SkillValidationContext context);
}
