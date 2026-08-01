package com.clawbot.wechatbot.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTests {
    @TempDir
    Path external;

    @Test
    void loadsAndExecutesExternalDefinition() throws Exception {
        write("demo", "demo");
        SkillManager manager = manager();
        SkillResult result = manager.execute(
            "DEMO", new SkillRequest("user", "run", "", "", ""));
        assertTrue(result.success());
        assertEquals("run", result.text());
        assertTrue(manager.plannerCatalog().contains("demo"));
    }

    @Test
    void rejectsDuplicateNameAcrossDefinitions() throws Exception {
        write("first", "demo");
        write("second", "demo");
        assertThrows(IllegalStateException.class, this::manager);
    }

    @Test
    void failedReloadKeepsPreviousAtomicSnapshot() throws Exception {
        write("demo", "demo");
        SkillManager manager = manager();
        Files.writeString(external.resolve("demo/skill.yaml"), "invalid: [");
        assertThrows(IllegalStateException.class, manager::reload);
        assertTrue(manager.contains("demo"));
    }

    private SkillManager manager() {
        SkillExecutor executor = new SkillExecutor() {
            @Override public String executorName() { return "demo"; }
            @Override public SkillResult execute(
                SkillDefinition definition, SkillRequest request
            ) {
                return SkillResult.success(request.instruction());
            }
        };
        return new SkillManager(new SkillDefinitionLoader(
            "classpath*:no-such-skills/*/skill.yaml",
            external, 10, 4096), List.of(executor), false, 100);
    }

    private void write(String directory, String name) throws Exception {
        Path folder = Files.createDirectories(external.resolve(directory));
        Files.writeString(folder.resolve("skill.yaml"), """
            name: %s
            version: 1.0.0
            enabled: true
            display-name: Demo
            description: demo skill
            executor: demo
            timeout-seconds: 10
            requires-user-context: true
            """.formatted(name));
    }
}
