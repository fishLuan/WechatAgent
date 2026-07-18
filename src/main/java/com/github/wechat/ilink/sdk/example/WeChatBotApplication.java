package com.github.wechat.ilink.sdk.example;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.example.config.BotConfig;
import com.github.wechat.ilink.sdk.example.base.MessageHandler;
import com.github.wechat.ilink.sdk.example.handler.ImageGenHandler;
import com.github.wechat.ilink.sdk.example.handler.ImageMessageHandler;
import com.github.wechat.ilink.sdk.example.handler.TextMessageHandler;
import com.github.wechat.ilink.sdk.example.service.ChatService;
import com.github.wechat.ilink.sdk.example.service.impl.AliyunDashscopeService;
import com.github.wechat.ilink.sdk.example.service.impl.DeepSeekChatService;
import com.github.wechat.ilink.sdk.example.util.QrCodeDisplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 机器人主入口 —— 只做"装配"工作：
 *   1. 加载配置
 *   2. 初始化服务（ChatService / VisionService / ImageGenService）
 *   3. 注册所有 MessageHandler（按优先级排序）
 *   4. 扫码登录 + 轮询消息
 *   5. 收到消息后依次交给 Handler 判断处理
 *
 * 业务逻辑不在此文件中，全部在 handler/ 和 service/ 下
 */
public class WeChatBotApplication {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("   WeChat iLink Bot - 分层架构版");
        System.out.println("========================================");
        System.out.println();

        // ============= 阶段一：加载配置 =============
        BotConfig config = new BotConfig();
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

        // ============= 阶段二：初始化服务 =============
        ChatService chatService = new DeepSeekChatService(config);
        // 阿里云服务已经存在，实例化它（同时实现 VisionService 和 ImageGenService）
        AliyunDashscopeService aliyunService = new AliyunDashscopeService(config.getDashscopeApiKey());

        // ============= 阶段三：注册所有 Handler =============
        List<MessageHandler> handlers = new ArrayList<>();
        handlers.add(new ImageMessageHandler(aliyunService));  // 优先级 10：图片 → 看图
        handlers.add(new ImageGenHandler(aliyunService));      // 优先级 50：画图指令 → 文生图
        handlers.add(new TextMessageHandler(chatService));     // 优先级 100：其他文本 → DeepSeek 对话

        // 按 priority 从小到大排序（小的优先尝试）
        handlers.sort(Comparator.comparingInt(MessageHandler::priority));

        System.out.println("[INFO] 已注册 " + handlers.size() + " 个消息处理器：");
        for (MessageHandler h : handlers) {
            System.out.println("       - " + h.getClass().getSimpleName() + " (priority=" + h.priority() + ")");
        }
        System.out.println();

        try {
            // ============= 阶段四：构建客户端 =============
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
                .onMessage(messages -> routeMessages(clientRef.get(), messages, handlers))
                .build();
            clientRef.set(client);

            // ============= 阶段五：扫码登录 =============
            System.out.println("[2/3] Getting QR code...");
            String qrContent = client.executeLogin();
            System.out.println();

            System.out.println("[3/3] Displaying QR code...");
            QrCodeDisplay.display(qrContent);

            System.out.println("[INFO] 请用微信扫码，等待登录...");
            System.out.println();

            // ============= 阶段六：登录就绪后开始轮询 =============
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

    // ============================================================
    // 消息路由：按优先级依次交给 Handler，第一个 canHandle 的来处理
    // ============================================================
    private static void routeMessages(ILinkClient client, List<WeixinMessage> messages, List<MessageHandler> handlers) {
        if (client == null || messages == null || messages.isEmpty()) return;

        for (WeixinMessage msg : messages) {
            if (msg == null) continue;

            // 依次尝试每个 Handler，第一个能处理的上
            boolean handled = false;
            for (MessageHandler handler : handlers) {
                if (handler.canHandle(msg)) {
                    handler.handle(client, msg);
                    handled = true;
                    break;
                }
            }

            if (!handled) {
                // 没有任何 Handler 能处理（比如只发了语音、文件等），静默忽略
                // 如果想提示用户，可以在这里加一个通用提示
            }
        }
    }
}