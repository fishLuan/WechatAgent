package WechatAI;

import WechatAI.config.AiProperties;
import WechatAI.config.AppConfigLoader;
import WechatAI.config.WechatClientFactory;
import WechatAI.service.AiChatService;
import WechatAI.service.DocumentAnalysisService;
import WechatAI.service.ImageGenerationService;
import WechatAI.service.ImageUnderstandingService;
import WechatAI.service.MessagePollingService;
import WechatAI.service.MessageTextExtractor;
import WechatAI.service.MemoryService;
import WechatAI.service.SpeechRecognitionService;
import WechatAI.service.SpeechSynthesisService;
import WechatAI.service.WechatMessageService;
import WechatAI.service.impl.QwenAiChatService;
import WechatAI.service.impl.QwenDocumentAnalysisService;
import WechatAI.service.impl.QwenImageGenerationService;
import WechatAI.service.impl.QwenImageUnderstandingService;
import WechatAI.service.impl.QwenSpeechRecognitionService;
import WechatAI.service.impl.QwenSpeechSynthesisService;
import WechatAI.service.impl.RedisMemoryService;
import WechatAI.service.impl.WechatMessagePollingService;
import WechatAI.service.impl.WechatMessageServiceImpl;
import WechatAI.service.impl.WechatMessageTextExtractor;
import WechatAI.support.WechatClientGateway;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.concurrent.ExecutionException;

/**
 * 应用启动入口，负责配置加载、微信登录和各业务服务的手动装配。
 */
@SpringBootApplication
public class AdvancedBotDemo {

    public static void main(String[] args) {
        AiProperties aiProperties = AppConfigLoader.load();
        ILinkClient client = WechatClientFactory.create();
        WechatClientGateway wechatClient = new WechatClientGateway(client);
        //生成二维码
        String qrCodeContent = client.executeLogin();
        System.out.println("📱 请将以下内容渲染为二维码后扫码登录：");
        System.out.println(qrCodeContent);

        if (!waitForLogin(client)) {
            return;
        }

        AiChatService aiChatService = new QwenAiChatService(aiProperties);
        DocumentAnalysisService documentAnalysisService = new QwenDocumentAnalysisService(aiChatService);
        ImageUnderstandingService imageUnderstandingService = new QwenImageUnderstandingService(aiProperties);
        ImageGenerationService imageGenerationService = new QwenImageGenerationService(aiProperties);
        SpeechRecognitionService speechRecognitionService = new QwenSpeechRecognitionService(aiProperties);
        SpeechSynthesisService speechSynthesisService = new QwenSpeechSynthesisService(aiProperties);
        MessageTextExtractor textExtractor = new WechatMessageTextExtractor();
        MemoryService memoryService = new RedisMemoryService(aiProperties);
        WechatMessageService messageService = new WechatMessageServiceImpl(
                wechatClient,
                aiChatService,
                documentAnalysisService,
                imageUnderstandingService,
                imageGenerationService,
                speechRecognitionService,
                speechSynthesisService,
                textExtractor,
                memoryService
        );
        MessagePollingService pollingService = new WechatMessagePollingService(wechatClient, messageService);

        System.out.println("🔄 开始监听消息...");
        pollingService.start();

        keepAlive();
    }

    private static boolean waitForLogin(ILinkClient client) {
        try {
            LoginContext context = client.getLoginFuture().get();
            System.out.println("✅ 恭喜登录成功，botId = " + context.getBotId());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("❌ 登录被中断: " + e.getMessage());
            return false;
        } catch (ExecutionException e) {
            System.err.println("❌ 登录失败: " + e.getMessage());
            return false;
        }
    }

    private static void keepAlive() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("程序已停止");
        }
    }
}
