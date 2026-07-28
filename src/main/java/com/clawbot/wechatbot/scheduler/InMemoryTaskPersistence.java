package com.clawbot.wechatbot.scheduler;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryTaskPersistence implements TaskPersistence {

    private final ConcurrentHashMap<String, ScheduledTask> store = new ConcurrentHashMap<>();
    private final TaskSchedulerProperties props;

    public InMemoryTaskPersistence(TaskSchedulerProperties props) {
        this.props = props;
    }

    @Override
    public void save(ScheduledTask task) {
        if (!props.isPersistenceEnabled()) return;
        store.put(task.id(), task);
    }

    @Override
    public void delete(String taskId) {
        store.remove(taskId);
    }

    @Override
    public Optional<ScheduledTask> findById(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<ScheduledTask> findByUserId(String userId) {
        return store.values().stream()
            .filter(t -> userId.equals(t.userId()))
            .sorted(Comparator.comparing(ScheduledTask::createdAt).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public List<ScheduledTask> findAllActive() {
        return store.values().stream()
            .filter(t -> t.status() == ScheduledTask.TaskStatus.PENDING)
            .sorted(Comparator.comparing(
                t -> t.nextFireTime() == null ? Instant.MAX : t.nextFireTime()))
            .collect(Collectors.toList());
    }
}