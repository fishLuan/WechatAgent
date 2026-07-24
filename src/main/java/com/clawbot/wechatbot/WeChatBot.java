package com.clawbot.wechatbot;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.notification.NotificationService;
import com.clawbot.wechatbot.util.QrCodeDisplay;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 微信机器人运行时。
 *
 * 所有依赖由 Spring 构造器注入；SmartLifecycle 负责随应用上下文启动和优雅关闭。
 */
@Component
@ConditionalOnProperty(name = "wechat.bot.enabled", havingValue = "true", matchIfMissing = true)
public class WeChatBot implements SmartLifecycle {
    private final BotConfig config;
    private final List<MessageHandler> handlers;
    private final NotificationService notifications;

    private volatile boolean running;
    private volatile ILinkClient client;
    private Thread pollingThread;

    public WeChatBot(BotConfig config, List<MessageHandler> handlers,
                     NotificationService notifications) {
        this.config = config;
        this.handlers = new ArrayList<>(handlers);
        this.handlers.sort(Comparator.comparingInt(MessageHandler::priority));
        this.notifications = notifications;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        printBanner();
        printConfigurationWarnings();
        printRegisteredHandlers();

        pollingThread = new Thread(this::runBot, "wechat-bot-polling");
        pollingThread.setDaemon(false);
        pollingThread.start();
    }

    private void runBot() {
        try {
            System.out.println("[1/3] Building client...");
            ILinkClient builtClient = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        System.out.println();
                        System.out.println("[OK] 登录成功!");
                        System.out.println("       Bot ID: " + ctx.getBotId());
                        System.out.println("       User ID: " + ctx.getUserId());
                        System.out.println("       现在可以在微信里给机器人发消息了");
                        System.out.println();
                        notifications.notifyLoginSuccess(ctx.getBotId(), ctx.getUserId());
                    }

                    @Override
                    public void onLoginFailure(Throwable th) {
                        System.err.println("[ERROR] 登录失败: " + th.getMessage());
                        notifications.notifyError("微信登录", th);
                    }
                })
                .onMessage(messages -> routeMessages(client, messages))
                .build();
            client = builtClient;

            System.out.println("[2/3] Getting QR code...");
            String qrContent = builtClient.executeLogin();
            notifications.notifyLoginRequired(qrContent);
            System.out.println();

            System.out.println("[3/3] Displaying QR code...");
            QrCodeDisplay.display(qrContent);
            System.out.println("[INFO] 请用微信扫码，等待登录...");
            System.out.println();

            while (running && !builtClient.isLoggedIn()) {
                Thread.sleep(1000);
            }
            if (!running) return;

            System.out.println("[INFO] 机器人运行中，按 Ctrl+C 退出");
            System.out.println();

            while (running) {
                try {
                    if (builtClient.isLoggedIn()) {
                        builtClient.getUpdates();
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (!running) break;
                    String message = e.getMessage();
                    if (message == null || !message.toLowerCase().contains("not logged")) {
                        System.err.println("[WARN] " + message);
                        notifications.notifyError("微信消息轮询", e);
                    }
                    Thread.sleep(3000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) {
                System.err.println("[FATAL] " + e.getMessage());
                e.printStackTrace();
                notifications.notifyError("微信机器人主线程", e);
            }
        } finally {
            running = false;
            closeClient();
        }
    }

    private void routeMessages(ILinkClient currentClient, List<WeixinMessage> messages) {
        if (currentClient == null || messages == null || messages.isEmpty()) return;
        for (WeixinMessage message : messages) {
            if (message == null) continue;
            for (MessageHandler handler : handlers) {
                try {
                    if (handler.canHandle(message)) {
                        handler.handle(currentClient, message);
                        break;
                    }
                } catch (Exception e) {
                    notifications.notifyError(
                        "消息处理器/" + handler.getClass().getSimpleName(), e);
                    System.err.println("[ERROR] 消息处理失败: " + e.getMessage());
                    break;
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        closeClient();
        if (pollingThread != null) pollingThread.interrupt();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private void closeClient() {
        ILinkClient current = client;
        client = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                System.err.println("[WARN] 关闭微信客户端失败: " + e.getMessage());
                notifications.notifyError("关闭微信客户端", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void printConfigurationWarnings() {
        if (config.isDingTalkNotificationEnabled()
            && config.getDingTalkWebhook().isBlank()) {
            warn("钉钉通知已启用，但机器人 Webhook 未配置", "DINGTALK_WEBHOOK");
        }
        if (!config.isDeepSeekConfigured()) {
            warn("DeepSeek API Key 未配置，文本对话将使用 Echo 模式", "DEEPSEEK_API_KEY");
        }
        if (!config.isDashscopeConfigured()) {
            warn("阿里云百炼 API Key 未配置，看图/画图功能不可用", "DASHSCOPE_API_KEY");
        }
        if (!config.isAmapWeatherConfigured()) {
            warn("高德天气 API Key 未配置，天气工具将返回配置提示", "AMAP_WEATHER_API_KEY");
        }
        if (!config.isJuheExchangeConfigured()) {
            warn("聚合数据汇率 API Key 未配置，汇率工具将返回配置提示", "JUHE_EXCHANGE_API_KEY");
        }
        if (!config.isBochaConfigured()) {
            System.out.println("[WARN] 博查AI搜索 API Key 未配置，将自动降级到必应 HTML 搜索");
            System.out.println();
        }
        if (!config.isTianapiConfigured()) {
            warn("天行数据 API Key 未配置，新闻工具将返回配置提示", "TIANAPI_API_KEY");
        }
    }

    private void warn(String message, String environmentVariable) {
        System.out.println("[WARN] " + message);
        System.out.println("       请配置环境变量 " + environmentVariable + " 后重启");
        System.out.println();
    }

    private void printRegisteredHandlers() {
        System.out.println("[INFO] Spring 已注入 " + handlers.size() + " 个消息处理器：");
        for (MessageHandler handler : handlers) {
            System.out.println("       - " + handler.getClass().getSimpleName()
                + " (priority=" + handler.priority() + ")");
        }
        System.out.println();
    }

    private void printBanner() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("   WeChat iLink Bot - Spring Boot");
        System.out.println("========================================");
        System.out.println();
    }
}
