package com.luanxv.pre.wechatAI;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.luanxv.pre.wechatAI.core.MessagePollingService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the iLink login alive and asks for a new QR scan when the session expires. */
@Component
@ConditionalOnProperty(name = "wechat.bot.enabled", havingValue = "true", matchIfMissing = true)
public class WeChatBot implements ApplicationRunner {
    private static final long LOGIN_TIMEOUT_MINUTES = 5;
    private final ILinkClient client;
    private final MessagePollingService pollingService;
    private final AtomicBoolean applicationRunning = new AtomicBoolean(true);
    private final AtomicBoolean reloginInProgress = new AtomicBoolean(false);
    private Thread sessionWatchdog;

    public WeChatBot(ILinkClient client, MessagePollingService pollingService) {
        this.client = client;
        this.pollingService = pollingService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        pollingService.setAuthenticationFailureHandler(
                exception -> requestRelogin("连续拉取消息时出现登录或令牌错误: " + exception.getMessage()));
        loginAndStartPolling("启动登录");
        startSessionWatchdog();
    }

    private void loginAndStartPolling(String reason) throws Exception {
        String qrCodeContent = client.executeLogin();
        System.out.println("需要扫码登录（" + reason + "）：");
        System.out.println(qrCodeContent);
        try {
            LoginContext context = client.getLoginFuture().get(LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            System.out.println("微信登录成功，botId = " + context.getBotId());
            pollingService.start();
        } catch (TimeoutException exception) {
            client.cancelLogin();
            throw new IllegalStateException("二维码在 " + LOGIN_TIMEOUT_MINUTES + " 分钟内未完成扫码", exception);
        }
    }

    private void startSessionWatchdog() {
        sessionWatchdog = new Thread(() -> {
            while (applicationRunning.get()) {
                LoginStatus.Status status = client.getLoginStatus().getStatus();
                if (status == LoginStatus.Status.EXPIRED || status == LoginStatus.Status.ERROR) {
                    requestRelogin("登录状态变为 " + status);
                }
                sleep(10_000);
            }
        }, "wechat-session-watchdog");
        sessionWatchdog.setDaemon(true);
        sessionWatchdog.start();
    }

    private void requestRelogin(String reason) {
        if (!applicationRunning.get() || !reloginInProgress.compareAndSet(false, true)) {
            return;
        }
        Thread recoveryThread = new Thread(() -> {
            try {
                pollingService.stop();
                client.clearAllContexts();
                while (applicationRunning.get()) {
                    try {
                        loginAndStartPolling(reason);
                        return;
                    } catch (Exception exception) {
                        System.err.println("重新登录失败，将重新生成二维码: " + exception.getMessage());
                        sleep(5_000);
                    }
                }
            } finally {
                reloginInProgress.set(false);
            }
        }, "wechat-relogin");
        recoveryThread.setDaemon(true);
        recoveryThread.start();
    }

    @PreDestroy
    public void shutdown() {
        applicationRunning.set(false);
        pollingService.stop();
        if (sessionWatchdog != null) {
            sessionWatchdog.interrupt();
        }
        client.cancelLogin();
        client.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
