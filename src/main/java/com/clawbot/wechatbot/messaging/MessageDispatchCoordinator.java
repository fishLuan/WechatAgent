package com.clawbot.wechatbot.messaging;

import java.util.function.Consumer;

/**
 * 微信消息执行协调器：同一用户保持顺序，不同用户允许并行。
 */
public interface MessageDispatchCoordinator extends AutoCloseable {

    /**
     * @return true 表示任务已进入执行队列；false 表示当前已停止或达到容量上限
     */
    boolean dispatch(
        String userId,
        Runnable task,
        Consumer<Throwable> failureHandler);

    @Override
    void close();
}
