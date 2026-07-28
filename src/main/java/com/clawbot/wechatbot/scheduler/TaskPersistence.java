package com.clawbot.wechatbot.scheduler;

import java.util.List;
import java.util.Optional;

public interface TaskPersistence {

    void save(ScheduledTask task);

    void delete(String taskId);

    Optional<ScheduledTask> findById(String taskId);

    List<ScheduledTask> findByUserId(String userId);

    List<ScheduledTask> findAllActive();
}