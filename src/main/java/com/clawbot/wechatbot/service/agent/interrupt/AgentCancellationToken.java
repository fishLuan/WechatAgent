package com.clawbot.wechatbot.service.agent.interrupt;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public boolean cancel() { return cancelled.compareAndSet(false, true); }
    public boolean isCancellationRequested() { return cancelled.get(); }
    public void checkpoint() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户已取消当前Agent任务");
        }
    }
}
