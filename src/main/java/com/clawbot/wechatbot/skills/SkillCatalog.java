package com.clawbot.wechatbot.skills;

import java.util.List;

/** Read-only skill catalog used by the task planner. */
public interface SkillCatalog {
    List<SkillDefinition> definitions();

    boolean contains(String name);

    default String plannerCatalog() {
        if (definitions().isEmpty()) return "(no domain skills are registered)";
        return definitions().stream()
            .map(skill -> "- " + skill.name() + ": " + skill.description()
                + (skill.capabilities().isEmpty()
                    ? ""
                    : "；capabilities=" + String.join(", ", skill.capabilities())))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }
}
