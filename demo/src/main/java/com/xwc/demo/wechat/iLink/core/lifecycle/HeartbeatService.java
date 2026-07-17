package com.xwc.demo.wechat.iLink.core.lifecycle;

import com.xwc.demo.wechat.iLink.core.listener.ListenerRegistry;
import com.xwc.demo.wechat.iLink.core.listener.OnHeartbeatListener;

import java.util.concurrent.*;

public class HeartbeatService implements AutoCloseable {
  private final ScheduledExecutorService scheduler;
  private final long intervalMs;
  private final HealthChecker healthChecker;
  private final ListenerRegistry registry;
  private ScheduledFuture<?> future;

  public HeartbeatService(
      ScheduledExecutorService scheduler,
      long intervalMs,
      HealthChecker healthChecker,
      ListenerRegistry registry) {
    this.scheduler = scheduler;
    this.intervalMs = intervalMs;
    this.healthChecker = healthChecker;
    this.registry = registry;
  }

  public synchronized void start() {
    if (future != null && !future.isDone()) return;
    future =
        scheduler.scheduleWithFixedDelay(
                () -> {
                  try {
                    healthChecker.check();
                    for (OnHeartbeatListener l : registry.getHeartbeatListeners())
                      l.onHeartbeatSuccess();
                  } catch (Throwable e) {
                    for (OnHeartbeatListener l : registry.getHeartbeatListeners())
                      l.onHeartbeatFailure(e);
                  }
                },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS);
  }

  public synchronized void stop() {
    if (future != null) {
      future.cancel(true);
      future = null;
    }
  }

  public void close() {
    stop();
  }
}