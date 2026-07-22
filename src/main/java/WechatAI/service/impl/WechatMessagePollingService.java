package WechatAI.service.impl;

import WechatAI.service.MessagePollingService;
import WechatAI.service.WechatMessageService;
import WechatAI.support.WechatClientGateway;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 微信消息轮询与异步派发服务，轮询线程只拉消息，业务处理交给线程池。
 */
public class WechatMessagePollingService implements MessagePollingService {

    private static final long EMPTY_POLL_INTERVAL_MS = 200L;
    private static final long IO_ERROR_RETRY_INTERVAL_MS = 5000L;
    private static final long UNKNOWN_ERROR_RETRY_INTERVAL_MS = 3000L;
    private static final int CORE_MESSAGE_WORKER_COUNT = 4;
    private static final int MAX_MESSAGE_WORKER_COUNT = 12;
    private static final int MESSAGE_QUEUE_CAPACITY = 24;

    private final WechatClientGateway wechatClient;
    private final WechatMessageService messageService;
    private final ThreadPoolExecutor messageExecutor;
    private volatile boolean running = true;

    public WechatMessagePollingService(WechatClientGateway wechatClient, WechatMessageService messageService) {
        this.wechatClient = wechatClient;
        this.messageService = messageService;
        this.messageExecutor = new ThreadPoolExecutor(
                CORE_MESSAGE_WORKER_COUNT,
                MAX_MESSAGE_WORKER_COUNT,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MESSAGE_QUEUE_CAPACITY),
                new MessageWorkerThreadFactory(),
                new BlockingRejectedExecutionHandler()
        );
    }

    @Override
    public void start() {
        Thread pollingThread = new Thread(this::poll, "wechat-message-polling");
        pollingThread.setDaemon(false);
        pollingThread.start();
    }

    @Override
    public void stop() {
        running = false;
        messageExecutor.shutdown();
        try {
            if (!messageExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                messageExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messageExecutor.shutdownNow();
        }
    }

    private void poll() {
        while (running) {
            try {
                List<WeixinMessage> messages = wechatClient.getUpdates();
                if (messages != null && !messages.isEmpty()) {
                    System.out.println("📥 本轮拉取到消息数: " + messages.size());
                    for (WeixinMessage message : messages) {
                        dispatchMessage(message);
                    }
                } else {
                    sleep(EMPTY_POLL_INTERVAL_MS);
                }
            } catch (IOException e) {
                System.err.println("⚠️ 拉取消息异常: " + e.getMessage());
                pause(IO_ERROR_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("消息轮询已停止");
                break;
            } catch (Exception e) {
                System.err.println("⚠️ 未知异常: " + e.getMessage());
                e.printStackTrace();
                pause(UNKNOWN_ERROR_RETRY_INTERVAL_MS);
            }
        }
    }

    private void dispatchMessage(WeixinMessage message) {
        logExecutorStatus("准备派发消息");
        try {
            messageExecutor.execute(() -> processMessage(message));
        } catch (RejectedExecutionException e) {
            System.err.println("⚠️ 消息处理线程池已停止或无法接收任务: " + e.getMessage());
            logExecutorStatus("线程池拒绝任务");
        }
    }

    private void processMessage(WeixinMessage message) {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        System.out.println("📨 开始异步处理消息: thread=" + threadName);
        try {
            messageService.handleAndReply(message);
        } catch (Exception e) {
            System.err.println("⚠️ 异步处理消息失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            long cost = System.currentTimeMillis() - startTime;
            System.out.println("📨 异步消息处理完成: thread=" + threadName + ", cost=" + cost + "ms");
            if (cost > 30000L) {
                System.err.println("⚠️ 单条消息处理耗时过长: " + cost + "ms");
            }
        }
    }

    private void logExecutorStatus(String scene) {
        System.out.println("📊 " + scene
                + ": active=" + messageExecutor.getActiveCount()
                + ", pool=" + messageExecutor.getPoolSize()
                + ", largest=" + messageExecutor.getLargestPoolSize()
                + ", queue=" + messageExecutor.getQueue().size()
                + "/" + MESSAGE_QUEUE_CAPACITY
                + ", completed=" + messageExecutor.getCompletedTaskCount());
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void pause(long millis) {
        try {
            sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static class MessageWorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "wechat-message-worker-" + index.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }

    private static class BlockingRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("message executor is shutdown");
            }
            try {
                System.err.println("⚠️ 消息处理线程池满载，等待空闲队列槽位");
                executor.getQueue().put(runnable);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while waiting for message queue", e);
            }
        }
    }
}
