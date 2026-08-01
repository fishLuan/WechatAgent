package com.clawbot.wechatbot.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads built-in and external skill definitions without loading executable code. */
public final class SkillDefinitionLoader {
    private final String classpathPattern;
    private final Path externalDirectory;
    private final int maxCount;
    private final int maxDefinitionBytes;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public SkillDefinitionLoader(
        String classpathPattern,
        Path externalDirectory,
        int maxCount,
        int maxDefinitionBytes
    ) {
        this.classpathPattern = classpathPattern;
        this.externalDirectory = externalDirectory.toAbsolutePath().normalize();
        this.maxCount = Math.max(1, maxCount);
        this.maxDefinitionBytes = Math.max(1024, maxDefinitionBytes);
    }

    public List<SkillDefinition> load() throws IOException {
        Map<String, SkillDefinition> definitions = new LinkedHashMap<>();
        Resource[] resources = new PathMatchingResourcePatternResolver()
            .getResources(classpathPattern);
        List<Resource> sortedResources = new ArrayList<>(List.of(resources));
        sortedResources.sort(Comparator.comparing(Resource::getDescription));
        for (Resource resource : sortedResources) {
            try (InputStream input = resource.getInputStream()) {
                add(definitions, readLimited(input, resource.getDescription()));
            }
        }
        loadExternal(definitions);
        return List.copyOf(definitions.values());
    }

    public Path externalDirectory() {
        return externalDirectory;
    }

    private void loadExternal(Map<String, SkillDefinition> definitions)
        throws IOException {
        Files.createDirectories(externalDirectory);
        Path root = externalDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        try (var children = Files.list(root)) {
            List<Path> files = children
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !Files.isSymbolicLink(path))
                .map(path -> path.resolve("skill.yaml"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !Files.isSymbolicLink(path))
                .sorted()
                .toList();
            for (Path file : files) {
                Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!real.startsWith(root)) {
                    throw new IOException("Skill definition escapes external directory: " + file);
                }
                try (InputStream input = Files.newInputStream(real)) {
                    add(definitions, readLimited(input, real.toString()));
                }
            }
        }
    }

    private SkillDefinition readLimited(InputStream input, String source)
        throws IOException {
        byte[] bytes = input.readNBytes(maxDefinitionBytes + 1);
        if (bytes.length > maxDefinitionBytes) {
            throw new IOException("Skill definition is too large: " + source);
        }
        try {
            return mapper.readValue(bytes, SkillDefinition.class);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid skill definition: " + source, exception);
        }
    }

    private void add(
        Map<String, SkillDefinition> definitions,
        SkillDefinition definition
    ) {
        if (!definition.enabled()) return;
        if (definitions.size() >= maxCount) {
            throw new IllegalStateException("Skill count exceeds limit: " + maxCount);
        }
        if (definitions.putIfAbsent(definition.name(), definition) != null) {
            throw new IllegalStateException("Duplicate skill name: " + definition.name());
        }
    }
}
