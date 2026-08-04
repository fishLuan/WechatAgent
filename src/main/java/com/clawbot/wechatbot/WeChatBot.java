package com.clawbot.wechatbot;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.base.PlanningBypassMessageHandler;
import com.clawbot.wechatbot.base.PlannedMessageHandler;
import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.feature.document.messaging.PendingWordDocumentInstructionStore;
import com.clawbot.wechatbot.feature.document.messaging.WordDocumentCommandParser;
import com.clawbot.wechatbot.memory.ConversationMemoryService;
import com.clawbot.wechatbot.messaging.MessageDispatchCoordinator;
import com.clawbot.wechatbot.messaging.WeChatClientRegistry;
import com.clawbot.wechatbot.notification.NotificationService;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.MultiTaskPlanningGate;
import com.clawbot.wechatbot.service.agent.TaskPlan;
import com.clawbot.wechatbot.util.QrCodeDisplay;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ConfigLoader;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final WeChatClientRegistry clientRegistry;
    private final ConversationMemoryService memoryService;
    private final MultiTaskPlanningGate planningGate;
    private final PendingWordDocumentInstructionStore pendingWordInstructions;
    private final MessageDispatchCoordinator messageDispatcher;
    private final Environment environment;
    private final BotSession session = new BotSession();
    private final String routeNamespace = "clawbot-" + UUID.randomUUID();
    private final AtomicBoolean consoleOpened = new AtomicBoolean(false);

    private volatile boolean running;

    public WeChatBot(BotConfig config, List<MessageHandler> handlers,
                     NotificationService notifications,
                     WeChatClientRegistry clientRegistry,
                     ConversationMemoryService memoryService,
                     MultiTaskPlanningGate planningGate,
                     PendingWordDocumentInstructionStore pendingWordInstructions,
                     MessageDispatchCoordinator messageDispatcher,
                     Environment environment) {
        this.config = config;
        this.handlers = new ArrayList<>(handlers);
        this.handlers.sort(Comparator.comparingInt(MessageHandler::priority));
        this.notifications = notifications;
        this.clientRegistry = clientRegistry;
        this.memoryService = memoryService;
        this.planningGate = planningGate;
        this.pendingWordInstructions = pendingWordInstructions;
        this.messageDispatcher = messageDispatcher;
        this.environment = environment;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        printBanner();
        printConfigurationWarnings();
        printRegisteredHandlers();

        session.start();
    }

    /**
     * 扫码登录成功后延迟自动打开可视化控制台（仅 Web 模式；打开失败不影响运行）。
     */
    private void openConsoleLater() {
        if (!consoleOpened.compareAndSet(false, true)) return;
        if ("none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "servlet"))) {
            System.out.println("[CONSOLE] 非 Web 模式，跳过自动打开控制台");
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                String port = environment.getProperty("local.server.port",
                    environment.getProperty("server.port", "8080"));
                String url = "http://localhost:" + port + "/console/index.html";
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    System.out.println("[CONSOLE] 🖥️ 已自动打开控制台: " + url);
                } else {
                    System.out.println("[CONSOLE] 桌面环境不支持自动打开，请手动访问: " + url);
                }
            } catch (Exception e) {
                System.out.println("[CONSOLE] 自动打开控制台失败（不影响运行）: " + e.getMessage());
            }
        }, "console-auto-open").start();
    }

    private void runBot(BotSession session) {
        try {
            System.out.println(session.prefix() + " [1/3] Building client...");
            ILinkClient builtClient = ILinkClient.builder()
                .config(createSessionConfig())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        System.out.println();
                        System.out.println(session.prefix() + " [OK] 登录成功!");
                        System.out.println("       Bot ID: " + ctx.getBotId());
                        System.out.println("       User ID: " + ctx.getUserId());
                        System.out.println("       现在可以在微信里给机器人发消息了");
                        System.out.println();
                        notifications.notifyLoginSuccess(ctx.getBotId(), ctx.getUserId());
                        openConsoleLater();
                    }

                    @Override
                    public void onLoginFailure(Throwable th) {
                        session.lastLoginFailure = th;
                    }
                })
                .onMessage(messages -> routeMessages(session.client, messages))
                .build();
            session.client = builtClient;
            clientRegistry.registerClient(builtClient);

            loginWithQrRefresh(session, builtClient);
            if (!running) return;

            System.out.println(session.prefix() + " [INFO] 机器人运行中，按 Ctrl+C 退出");
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
                        System.err.println(session.prefix() + " [WARN] " + message);
                        notifications.notifyError("微信消息轮询/" + session.name(), e);
                    }
                    Thread.sleep(3000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) {
                System.err.println(session.prefix() + " [FATAL] " + e.getMessage());
                e.printStackTrace();
                notifications.notifyError("微信机器人主线程/" + session.name(), e);
            }
        } finally {
            session.close();
        }
    }

    void routeMessages(ILinkClient currentClient, List<WeixinMessage> messages) {
        if (currentClient == null || messages == null || messages.isEmpty()) return;
        for (WeixinMessage message : messages) {
            if (message == null) continue;
            String from = message.getFrom_user_id();
            boolean accepted = messageDispatcher.dispatch(
                from,
                () -> processMessage(currentClient, message),
                error -> {
                    notifications.notifyError("微信消息异步处理", error);
                    System.err.println("[ERROR] 微信消息异步处理失败: "
                        + safeMessage(error));
                });
            if (!accepted) {
                System.err.println("[WECHAT-RECV] 消息队列已满，拒绝 messageId="
                    + message.getMessage_id() + " userId=" + from);
                sendBusyMessage(currentClient, from);
            }
        }
    }

    private void processMessage(
        ILinkClient currentClient,
        WeixinMessage message
    ) {
        String from = message.getFrom_user_id();
        if (isDuplicateMessage(from, message.getMessage_id())) {
            System.out.println("[WECHAT-RECV] 忽略重复消息 messageId="
                + message.getMessage_id() + " userId=" + from);
            return;
        }
        clientRegistry.bindUser(from, currentClient);
        recordRecentTextMessage(from, message);
        if (routeWithoutPlanning(currentClient, message)) return;
        Optional<TaskPlan> planningResult =
            planningGate.planDetailed(message);
        if (planningResult.filter(TaskPlan::limitExceeded).isPresent()) {
            String reply = planningResult.orElseThrow().userMessage();
            try {
                currentClient.sendText(from, reply);
                System.out.println("[SEND] " + reply);
            } catch (Exception error) {
                notifications.notifyError("发送任务超限提示", error);
                System.err.println("[ERROR] 发送任务超限提示失败: "
                    + safeMessage(error));
            }
            return;
        }
        Optional<List<AgentTask>> plannedTasks =
            planningResult.map(TaskPlan::tasks);
        boolean requiresUnifiedRoute =
            planningGate.hasSupportedAttachment(message)
                && !hasEditableWordAttachment(message);
        if (plannedTasks.filter(tasks ->
                tasks.size() > 1 || requiresUnifiedRoute).isPresent()
            && routePlannedMessage(
                currentClient, message, plannedTasks.orElseThrow())) {
            return;
        }
        for (MessageHandler handler : handlers) {
            try {
                if (handler.canHandle(message)) {
                    if (handler instanceof PlannedMessageHandler planned
                        && plannedTasks.isPresent()) {
                        planned.handlePlanned(
                            currentClient, message, plannedTasks.orElseThrow());
                    } else {
                        handler.handle(currentClient, message);
                    }
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

    private void sendBusyMessage(ILinkClient client, String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            client.sendText(
                userId,
                "当前消息较多，请稍后重新发送这条请求。");
        } catch (Exception error) {
            System.err.println("[WARN] 发送消息繁忙提示失败: "
                + safeMessage(error));
        }
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
            ? (error == null ? "未知错误" : error.getClass().getSimpleName())
            : message;
    }

    private boolean routePlannedMessage(
        ILinkClient currentClient,
        WeixinMessage message,
        List<AgentTask> tasks
    ) {
        PlannedMessageHandler plannedHandler = handlers.stream()
            .filter(PlannedMessageHandler.class::isInstance)
            .map(PlannedMessageHandler.class::cast)
            .findFirst()
            .orElse(null);
        if (plannedHandler == null) {
            System.err.println(
                "[WARN] 已识别多任务，但没有注册 PlannedMessageHandler，继续普通路由");
            return false;
        }
        try {
            plannedHandler.handlePlanned(currentClient, message, tasks);
            return true;
        } catch (Exception error) {
            notifications.notifyError(
                "消息处理器/" + plannedHandler.getClass().getSimpleName(), error);
            System.err.println("[ERROR] 多任务消息处理失败: " + error.getMessage());
            return true;
        }
    }

    private boolean hasEditableWordAttachment(WeixinMessage message) {
        if (message == null || message.getItem_list() == null) return false;
        for (MessageItem item : message.getItem_list()) {
            if (item.getFile_item() == null) continue;
            String fileName = item.getFile_item().getFile_name();
            if (fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) {
                return true;
            }
        }
        return false;
    }

    private void recordRecentTextMessage(String userId, WeixinMessage message) {
        if (hasEditableWordAttachment(message)) return;
        String text = extractText(message);
        if (text == null || text.isBlank()) return;
        String pendingInstruction =
            WordDocumentCommandParser.extractPendingFileInstruction(text);
        if (pendingInstruction != null) {
            pendingWordInstructions.put(userId, pendingInstruction);
        }
    }

    private boolean routeWithoutPlanning(
        ILinkClient currentClient,
        WeixinMessage message
    ) {
        for (MessageHandler handler : handlers) {
            if (!(handler instanceof PlanningBypassMessageHandler bypass)
                || !bypass.canBypassPlanning(message)) {
                continue;
            }
            try {
                handler.handle(currentClient, message);
            } catch (Exception error) {
                notifications.notifyError(
                    "消息处理器/" + handler.getClass().getSimpleName(), error);
                System.err.println("[ERROR] 消息处理失败: " + safeMessage(error));
            }
            return true;
        }
        return false;
    }

    private void loginWithQrRefresh(BotSession session, ILinkClient client)
        throws InterruptedException {
        int attempt = 0;
        while (running && !client.isLoggedIn()) {
            attempt++;
            session.lastLoginFailure = null;
            try {
                System.out.println(session.prefix() + " [2/3] Getting QR code... attempt=" + attempt);
                String qrContent = client.executeLogin();
                notifications.notifyLoginRequired(qrContent);
                System.out.println();
                System.out.println(session.prefix() + " [3/3] Displaying QR code...");
                QrCodeDisplay.display(qrContent,
                    "qrcode-login-" + System.currentTimeMillis() + ".html",
                    "微信扫码登录");
                System.out.println(session.prefix() + " [INFO] 请用微信扫码，等待登录...");
                System.out.println();
                client.getLoginFuture().get();
            } catch (ExecutionException error) {
                if (!running) return;
                Throwable cause = session.lastLoginFailure != null
                    ? session.lastLoginFailure
                    : error.getCause();
                if (cause == null) cause = error;
                String reason = safeMessage(cause);
                long retryDelayMillis = Math.min(10_000L, 1_500L * attempt);
                System.err.println(session.prefix() + " [WARN] 二维码登录失败或已过期："
                    + reason + "；" + retryDelayMillis + "ms 后自动刷新");
                notifications.notifyError("微信登录", cause);
                Thread.sleep(retryDelayMillis);
            } catch (RuntimeException error) {
                if (!running) return;
                long retryDelayMillis = Math.min(10_000L, 1_500L * attempt);
                System.err.println(session.prefix() + " [WARN] 获取二维码失败："
                    + safeMessage(error) + "；" + retryDelayMillis + "ms 后重试");
                notifications.notifyError("微信登录", error);
                Thread.sleep(retryDelayMillis);
            }
        }
    }

    private String extractText(WeixinMessage message) {
        if (message == null || message.getItem_list() == null) return null;
        StringBuilder text = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                text.append(item.getText_item().getText());
            }
        }
        return text.toString().trim();
    }

    private boolean isDuplicateMessage(String userId, Long messageId) {
        if (messageId == null || userId == null || userId.isBlank()) {
            return false;
        }
        try {
            return !memoryService.markMessageProcessed(userId, messageId);
        } catch (Exception e) {
            System.err.println("[WECHAT-RECV] 消息去重暂时不可用，将继续处理："
                + e.getMessage());
            notifications.notifyError("微信消息去重", e);
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        messageDispatcher.close();
        session.stop();
        clientRegistry.clear();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
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

    private ILinkConfig createSessionConfig() {
        ILinkConfig defaults = ConfigLoader.loadDefault();
        String configuredRouteTag = defaults.getRouteTag();
        String routeTagPrefix = configuredRouteTag == null || configuredRouteTag.isBlank()
            ? routeNamespace : configuredRouteTag.trim();

        return ILinkConfig.builder()
            .connectTimeoutMs(defaults.getConnectTimeoutMs())
            .readTimeoutMs(defaults.getReadTimeoutMs())
            .writeTimeoutMs(defaults.getWriteTimeoutMs())
            .httpMaxRetries(defaults.getHttpMaxRetries())
            .retryBaseDelayMs(defaults.getRetryBaseDelayMs())
            .retryMaxDelayMs(defaults.getRetryMaxDelayMs())
            .retryJitterEnabled(defaults.isRetryJitterEnabled())
            .loginTimeoutMs(defaults.getLoginTimeoutMs())
            .heartbeatEnabled(defaults.isHeartbeatEnabled())
            .heartbeatIntervalMs(defaults.getHeartbeatIntervalMs())
            .reconnectMaxAttempts(defaults.getReconnectMaxAttempts())
            .reconnectBaseDelayMs(defaults.getReconnectBaseDelayMs())
            .reconnectMaxDelayMs(defaults.getReconnectMaxDelayMs())
            .ioCoreThreads(defaults.getIoCoreThreads())
            .ioMaxThreads(defaults.getIoMaxThreads())
            .schedulerThreads(defaults.getSchedulerThreads())
            .queueCapacity(defaults.getQueueCapacity())
            .channelVersion(defaults.getChannelVersion())
            .autoReconnectEnabled(defaults.isAutoReconnectEnabled())
            .routeTag(routeTagPrefix + "-session")
            .build();
    }

    private final class BotSession {
        private volatile ILinkClient client;
        private volatile Throwable lastLoginFailure;
        private Thread pollingThread;

        private void start() {
            pollingThread = new Thread(() -> runBot(this), "wechat-bot-polling");
            pollingThread.setDaemon(false);
            pollingThread.start();
        }

        private void stop() {
            close();
            if (pollingThread != null) pollingThread.interrupt();
        }

        private void close() {
            ILinkClient current = client;
            client = null;
            if (current != null) {
                clientRegistry.unregisterClient(current);
                try {
                    current.close();
                } catch (Exception e) {
                    System.err.println(prefix() + " [WARN] 关闭微信客户端失败: " + e.getMessage());
                    notifications.notifyError("关闭微信客户端/" + name(), e);
                }
            }
        }

        private String name() {
            return "session";
        }

        private String prefix() {
            return "[WECHAT]";
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("   WeChat iLink Bot - Spring Boot");
        System.out.println("========================================");
        System.out.println();
    }

}
