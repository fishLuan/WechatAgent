package com.clawbot.wechatbot.messaging;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于用户任务链的消息调度器。
 *
 * <p>每个用户维护一条 CompletableFuture 链以保证消息顺序；不同用户的链
 * 由共享有界线程池并行执行。全局待处理计数同时限制尚未提交到线程池的
 * 用户链任务，避免单个用户无限堆积。</p>
 */
public final class PerUserMessageDispatchCoordinator
    implements MessageDispatchCoordinator {

    private static final String ANONYMOUS_USER = "__anonymous_wechat_user__";

    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> userTails =
        new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final int maxPendingMessages;
    private final Duration shutdownWait;
    private final Object completionMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean accepting = true;

    public PerUserMessageDispatchCoordinator(
        int parallelism,
        int maxPendingMessages,
        Duration shutdownWait
    ) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("消息并行度必须大于0");
        }
        if (maxPendingMessages <= 0) {
            throw new IllegalArgumentException("消息等待上限必须大于0");
        }
        if (shutdownWait == null
            || shutdownWait.isZero()
            || shutdownWait.isNegative()) {
            throw new IllegalArgumentException("消息关闭等待时间必须大于0");
        }
        this.maxPendingMessages = maxPendingMessages;
        this.shutdownWait = shutdownWait;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(maxPendingMessages),
            runnable -> {
                Thread thread = new Thread(
                    runnable,
                    "wechat-message-worker-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public boolean dispatch(
        String userId,
        Runnable task,
        Consumer<Throwable> failureHandler
    ) {
        Objects.requireNonNull(task, "task");
        Consumer<Throwable> safeFailureHandler =
            failureHandler == null ? ignored -> { } : failureHandler;
        if (!reservePendingSlot()) return false;

        String key = normalizeUserId(userId);
        AtomicReference<CompletableFuture<Void>> created = new AtomicReference<>();
        try {
            userTails.compute(key, (ignored, previous) -> {
                CompletableFuture<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((result, error) -> null);
                CompletableFuture<Void> next = predecessor.thenRunAsync(
                    task, executor);
                created.set(next);
                return next;
            });
        } catch (RuntimeException schedulingFailure) {
            releasePendingSlot();
            safeAccept(safeFailureHandler, schedulingFailure);
            return false;
        }

        CompletableFuture<Void> future = created.get();
        future.whenComplete((ignored, error) -> {
            userTails.compute(
                key,
                (currentKey, currentTail) ->
                    currentTail == future ? null : currentTail);
            releasePendingSlot();
            Throwable cause = unwrap(error);
            if (cause != null) safeAccept(safeFailureHandler, cause);
        });
        return true;
    }

    public int pendingCount() {
        return pending.get();
    }

    private boolean reservePendingSlot() {
        while (accepting) {
            int current = pending.get();
            if (current >= maxPendingMessages) return false;
            if (pending.compareAndSet(current, current + 1)) {
                if (accepting) return true;
                releasePendingSlot();
                return false;
            }
        }
        return false;
    }

    private void releasePendingSlot() {
        pending.decrementAndGet();
        synchronized (completionMonitor) {
            completionMonitor.notifyAll();
        }
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank()
            ? ANONYMOUS_USER
            : userId.trim();
    }

    private Throwable unwrap(Throwable error) {
        if (error == null) return null;
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private void safeAccept(
        Consumer<Throwable> failureHandler,
        Throwable error
    ) {
        try {
            failureHandler.accept(error);
        } catch (RuntimeException ignored) {
            // 错误通知本身不能破坏消息调度线程。
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        accepting = false;
        long deadline = System.nanoTime() + shutdownWait.toNanos();
        synchronized (completionMonitor) {
            while (pending.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(completionMonitor, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        executor.shutdown();
        long remaining = Math.max(0, deadline - System.nanoTime());
        try {
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        userTails.clear();
    }
}
