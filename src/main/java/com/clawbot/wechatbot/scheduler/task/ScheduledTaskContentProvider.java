package com.clawbot.wechatbot.scheduler.task;

import com.clawbot.wechatbot.scheduler.model.TaskType;

public interface ScheduledTaskContentProvider {
    TaskType taskType();
    String provideContent(String userId, String paramsJson) throws Exception;
}
