package com.clawbot.wechatbot.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic runtime registry with optional hot reload for external skill.yaml files. */
public final class SkillManager implements SkillCatalog, SmartLifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    private final SkillDefinitionLoader loader;
    private final Map<String, SkillExecutor> executors;
    private final boolean watchEnabled;
    private final long debounceMillis;
    private final AtomicReference<Map<String, LoadedSkill>> snapshot =
        new AtomicReference<>(Map.of());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile WatchService watchService;
    private volatile Thread watchThread;

    public SkillManager(
        SkillDefinitionLoader loader,
        List<SkillExecutor> discoveredExecutors,
        boolean watchEnabled,
        long debounceMillis
    ) {
        this.loader = loader;
        this.executors = indexExecutors(discoveredExecutors);
        this.watchEnabled = watchEnabled;
        this.debounceMillis = Math.max(100, debounceMillis);
        reload();
    }

    public synchronized void reload() {
        try {
            Map<String, LoadedSkill> next = new LinkedHashMap<>();
            for (SkillDefinition definition : loader.load()) {
                SkillExecutor executor = executors.get(definition.executor());
                if (executor == null) {
                    throw new IllegalStateException(
                        "No executor registered for skill " + definition.name()
                            + ": " + definition.executor());
                }
                next.put(definition.name(), new LoadedSkill(definition, executor));
            }
            snapshot.set(Map.copyOf(next));
            log.info("Skill catalog loaded: {}", next.keySet());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load skill catalog", exception);
        }
    }

    public SkillResult execute(String name, SkillRequest request) throws Exception {
        LoadedSkill skill = snapshot.get().get(normalize(name));
        if (skill == null) return SkillResult.failure("Unknown skill: " + name);
        if (skill.definition().requiresUserContext()
            && (request == null || request.userId().isBlank())) {
            return SkillResult.failure("Skill requires user context: " + name);
        }
        return skill.executor().execute(skill.definition(), request);
    }

    @Override
    public List<SkillDefinition> definitions() {
        return snapshot.get().values().stream().map(LoadedSkill::definition).toList();
    }

    @Override
    public boolean contains(String name) {
        return snapshot.get().containsKey(normalize(name));
    }

    public int size() {
        return snapshot.get().size();
    }

    public List<String> names() {
        return List.copyOf(snapshot.get().keySet());
    }

    @Override
    public void start() {
        if (!watchEnabled || !running.compareAndSet(false, true)) return;
        try {
            Path root = loader.externalDirectory();
            Files.createDirectories(root);
            watchService = FileSystems.getDefault().newWatchService();
            registerDirectories(root);
            watchThread = Thread.ofPlatform().daemon()
                .name("skill-definition-watcher").start(this::watchLoop);
        } catch (IOException exception) {
            running.set(false);
            throw new IllegalStateException("Failed to start skill watcher", exception);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        closeQuietly();
        if (watchThread != null) watchThread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();
                boolean changed = !key.pollEvents().isEmpty();
                key.reset();
                if (!changed) continue;
                Thread.sleep(debounceMillis);
                registerDirectories(loader.externalDirectory());
                try {
                    reload();
                } catch (RuntimeException exception) {
                    log.error("Skill hot reload failed; previous catalog remains active", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                if (running.get()) log.error("Skill watcher failed", exception);
            }
        }
    }

    private void registerDirectories(Path root) throws IOException {
        root.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        try (var children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (!Files.isSymbolicLink(child)) {
                    child.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                }
            }
        }
    }

    private Map<String, SkillExecutor> indexExecutors(List<SkillExecutor> discovered) {
        Map<String, SkillExecutor> result = new LinkedHashMap<>();
        if (discovered == null) return Map.of();
        for (SkillExecutor executor : discovered) {
            String name = normalize(executor.executorName());
            if (name.isBlank() || result.putIfAbsent(name, executor) != null) {
                throw new IllegalStateException("Invalid or duplicate skill executor: " + name);
            }
        }
        return Map.copyOf(result);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void closeQuietly() {
        if (watchService == null) return;
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
    }

    private record LoadedSkill(SkillDefinition definition, SkillExecutor executor) {
    }
}
