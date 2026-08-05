package com.clawbot.wechatbot.skills.validation;

import java.util.Locale;

/** skill.yaml 中的校验声明。 */
public record SkillValidationDefinition(
    SkillValidationMode mode,
    String validator
) {
    public SkillValidationDefinition {
        mode = mode == null ? SkillValidationMode.GENERIC : mode;
        validator = validator == null
            ? "" : validator.trim().toLowerCase(Locale.ROOT);
        if (mode == SkillValidationMode.REQUIRED && validator.isBlank()) {
            throw new IllegalArgumentException("REQUIRED validation must name a validator");
        }
    }

    public static SkillValidationDefinition generic() {
        return new SkillValidationDefinition(SkillValidationMode.GENERIC, "");
    }
}
