package com.luanxv.pre.wechatAI.core;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class MessagePollingService {
    private final ILinkClient client;
    private final MessageHandler messageHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Consumer<Exception> authenticationFailureHandler = ignored -> { };
    private Thread pollingThread;

    public MessagePollingService(ILinkClient client, MessageHandler messageHandler) {
        this.client = client;
        this.messageHandler = messageHandler;
    }

    public void setAuthenticationFailureHandler(Consumer<Exception> handler) {
        this.authenticationFailureHandler = handler == null ? ignored -> { } : handler;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consecutiveFailures.set(0);
        pollingThread = new Thread(this::pollMessages, "wechat-message-poller");
        pollingThread.setDaemon(false);
        pollingThread.start();
    }

    private void pollMessages() {
        while (running.get()) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                consecutiveFailures.set(0);
                if (messages != null) {
                    for (WeixinMessage message : messages) {
                        messageHandler.handleAndReply(message);
                    }
                }
                Thread.sleep(1_000);
            } catch (IOException exception) {
                int failures = consecutiveFailures.incrementAndGet();
                System.err.println("拉取微信消息失败(" + failures + "): " + exception.getMessage());
                if (failures >= 3 && isLikelyAuthenticationFailure(exception)) {
                    authenticationFailureHandler.accept(exception);
                }
                sleep(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                System.err.println("处理微信轮询失败: " + exception.getMessage());
                sleep(3_000);
            }
        }
    }

    private boolean isLikelyAuthenticationFailure(Exception exception) {
        if (!client.isLoggedIn()) {
            return true;
        }
        String message = String.valueOf(exception.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("401") || message.contains("unauthor") || message.contains("expired")
                || message.contains("token") || message.contains("not login") || message.contains("context");
    }

    public synchronized void stop() {
        running.set(false);
        Thread threadToStop = pollingThread;
        if (threadToStop != null) {
            threadToStop.interrupt();
            if (threadToStop != Thread.currentThread()) {
                try {
                    // getUpdates has a 35-second client read timeout; wait long enough to prevent duplicate pollers.
                    threadToStop.join(40_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        pollingThread = null;
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
