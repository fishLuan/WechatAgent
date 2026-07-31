package com.clawbot.wechatbot.skills;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Declarative metadata loaded from a skill.yaml file. */
public record SkillDefinition(
    String name,
    String version,
    boolean enabled,
    @JsonProperty("display-name") String displayName,
    String description,
    String executor,
    List<String> capabilities,
    List<String> triggers,
    @JsonProperty("timeout-seconds") int timeoutSeconds,
    @JsonProperty("requires-user-context") boolean requiresUserContext
) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public SkillDefinition {
        name = normalize(name);
        executor = normalize(executor);
        version = safe(version);
        displayName = safe(displayName);
        description = safe(description);
        capabilities = immutableStrings(capabilities);
        triggers = immutableStrings(triggers);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid skill name: " + name);
        }
        if (executor.isBlank()) {
            throw new IllegalArgumentException("Skill executor cannot be empty: " + name);
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("Skill description cannot be empty: " + name);
        }
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }
}
