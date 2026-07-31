package com.clawbot.wechatbot.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerUserMessageDispatchCoordinatorTests {

    @Test
    void runsDifferentUsersInParallel() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);

        try (PerUserMessageDispatchCoordinator dispatcher =
                 dispatcher(2, 10)) {
            assertTrue(dispatcher.dispatch(
                "user-a",
                blockingTask(bothStarted, release, completed),
                error -> { }));
            assertTrue(dispatcher.dispatch(
                "user-b",
                blockingTask(bothStarted, release, completed),
                error -> { }));

            assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void preservesOrderForSameUser() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        try (PerUserMessageDispatchCoordinator dispatcher =
                 dispatcher(2, 10)) {
            assertTrue(dispatcher.dispatch("same-user", () -> {
                order.add("first-start");
                firstStarted.countDown();
                await(releaseFirst);
                order.add("first-end");
            }, error -> { }));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(dispatcher.dispatch("same-user", () -> {
                order.add("second");
                secondStarted.countDown();
            }, error -> { }));

            assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertEquals(
                List.of("first-start", "first-end", "second"),
                order);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void rejectsWhenGlobalPendingLimitIsReached() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (PerUserMessageDispatchCoordinator dispatcher =
                 dispatcher(1, 1)) {
            assertTrue(dispatcher.dispatch("user-a", () -> {
                started.countDown();
                await(release);
            }, error -> { }));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertFalse(dispatcher.dispatch(
                "user-b", () -> { }, error -> { }));
            assertEquals(1, dispatcher.pendingCount());
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void failureDoesNotBlockNextMessageFromSameUser() throws Exception {
        CountDownLatch secondCompleted = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);

        try (PerUserMessageDispatchCoordinator dispatcher =
                 dispatcher(1, 10)) {
            assertTrue(dispatcher.dispatch(
                "same-user",
                () -> {
                    throw new IllegalStateException("模拟失败");
                },
                error -> failureReported.countDown()));
            assertTrue(dispatcher.dispatch(
                "same-user",
                secondCompleted::countDown,
                error -> { }));

            assertTrue(failureReported.await(1, TimeUnit.SECONDS));
            assertTrue(secondCompleted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeWaitsForAcceptedMessageToFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        PerUserMessageDispatchCoordinator dispatcher = dispatcher(1, 2);
        try {
            assertTrue(dispatcher.dispatch("user-a", () -> {
                started.countDown();
                await(release);
            }, error -> { }));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            Thread closer = new Thread(() -> {
                dispatcher.close();
                closed.countDown();
            });
            closer.start();
            assertFalse(closed.await(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            closer.join(1000);
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }

    private PerUserMessageDispatchCoordinator dispatcher(
        int parallelism,
        int maxPending
    ) {
        return new PerUserMessageDispatchCoordinator(
            parallelism, maxPending, Duration.ofSeconds(2));
    }

    private Runnable blockingTask(
        CountDownLatch started,
        CountDownLatch release,
        CountDownLatch completed
    ) {
        return () -> {
            started.countDown();
            await(release);
            completed.countDown();
        };
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
