package com.clawbot.wechatbot.feature.bilibili.scheduling;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.time.Instant;
import java.time.LocalTime;

/** B站应用层使用的调度端口，隔离通用调度器的持久化模型。 */
public interface BilibiliSchedulePort {
    void scheduleOneTime(
        String wechatUserId, ContentType contentType, int count, Instant fireAt);

    void scheduleDaily(
        String wechatUserId, ContentType contentType, int count, LocalTime pushTime);
}
