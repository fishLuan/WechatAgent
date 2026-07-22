package com.clawbot.wechatbot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.handler.DocumentMessageHandler;
import com.clawbot.wechatbot.handler.ImageGenHandler;
import com.clawbot.wechatbot.handler.ImageMessageHandler;
import com.clawbot.wechatbot.handler.TextMessageHandler;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.service.VisionService;
import com.clawbot.wechatbot.service.client.DashScopeClient;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.impl.DashScopeImageGenService;
import com.clawbot.wechatbot.service.impl.DashScopeSpeechSynthesisService;
import com.clawbot.wechatbot.service.impl.DashScopeVisionService;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.tools.AmapWeatherTool;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.util.QrCodeDisplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 机器人核心 —— 负责装配（配置/服务/处理器）和运行（登录+轮询）
 *
 * 设计：
 *   - 构造器 initialize()：加载配置、初始化服务、注册 Handler
 *   - start()：构建客户端、扫码登录、进入轮询循环
 *   - routeMessages()：消息分发（按 priority 依次匹配）
 *
 * 启动入口在 WeChatBotApplication，只做 new WeChatBot().start();
 */
public class WeChatBot {

    private BotConfig config;
    private List<MessageHandler> handlers;

    public WeChatBot() {
        initialize();
    }

    // ========== 阶段一 ~ 阶段三：装配 ==========

    private void initialize() {
        DocumentService.silencePdfLogs();
        printBanner();

        config = new BotConfig();
        if (!config.isDeepSeekConfigured()) {
            System.out.println("[WARN] DeepSeek API Key 未配置，文本对话将使用 Echo 模式");
            System.out.println("       请配置环境变量 DEEPSEEK_API_KEY 后重启");
            System.out.println();
        }
        if (!config.isDashscopeConfigured()) {
            System.out.println("[WARN] 阿里云百炼 API Key 未配置，看图/画图功能不可用");
            System.out.println("       请配置环境变量 DASHSCOPE_API_KEY 后重启");
            System.out.println();
        }
        if (!config.isAmapWeatherConfigured()) {
            System.out.println("[WARN] 高德天气 API Key 未配置，天气 function-calling 将返回配置提示");
            System.out.println("       请配置环境变量 AMAP_WEATHER_API_KEY 后重启");
            System.out.println();
        }

        DeepSeekClient deepSeekClient = new DeepSeekClient(
            config.getDeepSeekApiKey(), config.getDeepSeekModel(), config.getDeepSeekUrl(),
            config.getDeepSeekTemperature(), config.getDeepSeekMaxTokens(),
            config.getDeepSeekConnectTimeoutSeconds(), config.getDeepSeekRequestTimeoutSeconds());
        FunctionToolRegistry toolRegistry = new FunctionToolRegistry(deepSeekClient.mapper())
            .register(new AmapWeatherTool(
                config.getAmapWeatherApiKey(), config.getAmapWeatherEndpoint(),
                config.getAmapConnectTimeoutSeconds(), config.getAmapRequestTimeoutSeconds()));
        ChatService chatService = new DeepSeekChatService(
            deepSeekClient, toolRegistry, config.getSystemPrompt(), config.getDeepSeekMaxToolRounds());

        DashScopeClient dashScopeClient = new DashScopeClient(
            config.getDashscopeApiKey(), config.getDashscopeEndpoint(),
            config.getDashscopeConnectTimeoutSeconds(), config.getDashscopeRequestTimeoutSeconds());
        VisionService visionService = new DashScopeVisionService(
            dashScopeClient, config.getVisionModel(), config.getVisionDefaultQuestion());
        ImageGenService imageGenService = new DashScopeImageGenService(
            dashScopeClient, config.getImageModel(), config.getImageDefaultSize(),
            config.getImageDefaultCount(), config.isImagePromptExtend(), config.isImageWatermark());
        SpeechSynthesisService speechService = new DashScopeSpeechSynthesisService(
            dashScopeClient, config.getTtsModel(), config.getTtsDefaultVoice(),
            config.getTtsFormat(), config.getTtsMaxTextLength());
        DocumentService documentService = new DocumentService();

        handlers = new ArrayList<>();
        handlers.add(new ImageMessageHandler(visionService));
        handlers.add(new ImageGenHandler(imageGenService));
        handlers.add(new DocumentMessageHandler(chatService, documentService));
        SpeechSynthesisService ttsService = config.isDashscopeConfigured() ? speechService : null;
        handlers.add(new TextMessageHandler(chatService, ttsService, documentService));

        handlers.sort(Comparator.comparingInt(MessageHandler::priority));

        System.out.println("[INFO] 已注册 " + handlers.size() + " 个消息处理器：");
        for (MessageHandler h : handlers) {
            System.out.println("       - " + h.getClass().getSimpleName() + " (priority=" + h.priority() + ")");
        }
        System.out.println();
    }

    // ========== 阶段四 ~ 阶段六：登录+轮询 ==========

    public void start() {
        try {
            System.out.println("[1/3] Building client...");
            AtomicReference<ILinkClient> clientRef = new AtomicReference<>();

            ILinkClient client = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        System.out.println();
                        System.out.println("[OK] 登录成功!");
                        System.out.println("       Bot ID: " + ctx.getBotId());
                        System.out.println("       User ID: " + ctx.getUserId());
                        System.out.println("       现在可以在微信里给机器人发消息了");
                        System.out.println();
                    }
                    @Override
                    public void onLoginFailure(Throwable th) {
                        System.err.println("[ERROR] 登录失败: " + th.getMessage());
                    }
                })
                .onMessage(messages -> routeMessages(clientRef.get(), messages))
                .build();
            clientRef.set(client);

            System.out.println("[2/3] Getting QR code...");
            String qrContent = client.executeLogin();
            System.out.println();

            System.out.println("[3/3] Displaying QR code...");
            QrCodeDisplay.display(qrContent);

            System.out.println("[INFO] 请用微信扫码，等待登录...");
            System.out.println();

            while (!client.isLoggedIn()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }

            System.out.println("[INFO] 机器人运行中，按 Ctrl+C 退出");
            System.out.println();

            while (true) {
                try {
                    if (client.isLoggedIn()) {
                        client.getUpdates();
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg == null || !msg.toLowerCase().contains("not logged")) {
                        System.err.println("[WARN] " + msg);
                    }
                    Thread.sleep(3000);
                }
            }
        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== 消息路由 ==========

    private void routeMessages(ILinkClient client, List<WeixinMessage> messages) {
        if (client == null || messages == null || messages.isEmpty()) return;
        for (WeixinMessage msg : messages) {
            if (msg == null) continue;
            for (MessageHandler handler : handlers) {
                if (handler.canHandle(msg)) {
                    handler.handle(client, msg);
                    break;
                }
            }
        }
    }

    // ========== 工具 ==========

    private void printBanner() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("   WeChat iLink Bot - 分层架构版");
        System.out.println("========================================");
        System.out.println();
    }
}
