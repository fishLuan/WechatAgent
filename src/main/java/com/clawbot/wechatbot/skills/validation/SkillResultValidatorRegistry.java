package com.clawbot.wechatbot.skills.validation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 自动索引 Spring 发现的专属 Skill 校验器，核心流程不包含领域 if-else。 */
public final class SkillResultValidatorRegistry {
    private final Map<String, List<SkillResultValidator>> validators;

    public SkillResultValidatorRegistry(List<SkillResultValidator> discovered) {
        Map<String, List<SkillResultValidator>> indexed = new LinkedHashMap<>();
        if (discovered != null) {
            for (SkillResultValidator validator : discovered) {
                String name = normalize(validator.validatorName());
                if (name.isBlank()) {
                    throw new IllegalArgumentException("Skill validator name cannot be empty");
                }
                indexed.computeIfAbsent(name, ignored -> new ArrayList<>()).add(validator);
            }
        }
        indexed.values().forEach(list ->
            list.sort(Comparator.comparingInt(SkillResultValidator::order)));
        Map<String, List<SkillResultValidator>> immutable = new LinkedHashMap<>();
        indexed.forEach((name, list) -> immutable.put(name, List.copyOf(list)));
        this.validators = Map.copyOf(immutable);
    }

    public List<SkillResultValidator> validatorsFor(String name) {
        return validators.getOrDefault(normalize(name), List.of());
    }

    public boolean contains(String name) {
        return !validatorsFor(name).isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
