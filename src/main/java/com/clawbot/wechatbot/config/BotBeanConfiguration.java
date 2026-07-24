package com.clawbot.wechatbot.config;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.handler.DocumentMessageHandler;
import com.clawbot.wechatbot.handler.ImageGenHandler;
import com.clawbot.wechatbot.handler.ImageMessageHandler;
import com.clawbot.wechatbot.handler.TextMessageHandler;
import com.clawbot.wechatbot.notification.DingTalkNotificationService;
import com.clawbot.wechatbot.notification.NoOpNotificationService;
import com.clawbot.wechatbot.notification.NotificationService;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.service.VisionService;
import com.clawbot.wechatbot.service.client.DashScopeClient;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.document.PdfDocumentService;
import com.clawbot.wechatbot.service.document.WordDocumentService;
import com.clawbot.wechatbot.service.impl.DashScopeImageGenService;
import com.clawbot.wechatbot.service.impl.DashScopeSpeechSynthesisService;
import com.clawbot.wechatbot.service.impl.DashScopeVisionService;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.tools.UrlSafetyCheckerTool.UrlSafetyChecker;
import com.clawbot.wechatbot.tools.exchangeratetool.ExchangeRateTool;
import com.clawbot.wechatbot.tools.searchonlinetool.WebSearchTool;
import com.clawbot.wechatbot.tools.searchweathertool.AmapWeatherTool;
import com.clawbot.wechatbot.tools.tiannewstool.TianNewsTool;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 应用对象装配中心。业务类保持纯 Java 构造器，生命周期和依赖关系由 Spring 管理。
 */
@Configuration(proxyBeanMethods = false)
public class BotBeanConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean(destroyMethod = "close")
    NotificationService notificationService(BotConfig config, ObjectMapper mapper) {
        if (!config.isDingTalkNotificationConfigured()) {
            return new NoOpNotificationService();
        }
        return new DingTalkNotificationService(
            config.getDingTalkWebhook(),
            config.getDingTalkSecret(),
            config.getDingTalkTimeoutSeconds(),
            config.getDingTalkErrorDeduplicateSeconds(),
            mapper);
    }

    @Bean
    DeepSeekClient deepSeekClient(BotConfig config) {
        return new DeepSeekClient(
            config.getDeepSeekApiKey(), config.getDeepSeekModel(), config.getDeepSeekUrl(),
            config.getDeepSeekTemperature(), config.getDeepSeekMaxTokens(),
            config.getDeepSeekConnectTimeoutSeconds(), config.getDeepSeekRequestTimeoutSeconds());
    }

    @Bean
    DashScopeClient dashScopeClient(BotConfig config) {
        return new DashScopeClient(
            config.getDashscopeApiKey(), config.getDashscopeEndpoint(),
            config.getDashscopeConnectTimeoutSeconds(), config.getDashscopeRequestTimeoutSeconds());
    }

    @Bean
    AmapWeatherTool amapWeatherTool(BotConfig config) {
        return new AmapWeatherTool(
            config.getAmapWeatherApiKey(), config.getAmapWeatherEndpoint(),
            config.getAmapConnectTimeoutSeconds(), config.getAmapRequestTimeoutSeconds());
    }

    @Bean
    ExchangeRateTool exchangeRateTool(BotConfig config) {
        return new ExchangeRateTool(
            config.getJuheExchangeApiKey(), config.getJuheExchangeEndpoint(),
            config.getJuheExchangeVersion(), config.getJuheExchangeConnectTimeoutSeconds(),
            config.getJuheExchangeRequestTimeoutSeconds());
    }

    @Bean
    WebSearchTool webSearchTool(BotConfig config) {
        return new WebSearchTool(
            config.getBochaApiKey(), config.getBochaEndpoint(),
            config.getBochaConnectTimeoutSeconds(), config.getBochaRequestTimeoutSeconds());
    }

    @Bean
    TianNewsTool tianNewsTool(BotConfig config) {
        return new TianNewsTool(config.getTianapiApiKey());
    }

    @Bean
    WebPageExtractTool webPageExtractTool(BotConfig config) {
        return new WebPageExtractTool(
            config.getWebPageExtractConnectTimeoutSeconds(),
            config.getWebPageExtractRequestTimeoutSeconds(),
            config.getWebPageExtractMaxBodyChars());
    }

    @Bean
    UrlSafetyChecker urlSafetyChecker(ObjectMapper mapper) {
        return new UrlSafetyChecker(mapper);
    }

    @Bean
    FunctionToolRegistry functionToolRegistry(ObjectMapper mapper, List<FunctionTool> tools) {
        return new FunctionToolRegistry(mapper, tools);
    }

    @Bean
    ChatService chatService(DeepSeekClient client, FunctionToolRegistry registry, BotConfig config) {
        return new DeepSeekChatService(
            client, registry, config.getSystemPrompt(), config.getDeepSeekMaxToolRounds());
    }

    @Bean
    VisionService visionService(DashScopeClient client, BotConfig config) {
        return new DashScopeVisionService(
            client, config.getVisionModel(), config.getVisionDefaultQuestion());
    }

    @Bean
    ImageGenService imageGenService(DashScopeClient client, BotConfig config) {
        return new DashScopeImageGenService(
            client, config.getImageModel(), config.getImageDefaultSize(),
            config.getImageDefaultCount(), config.isImagePromptExtend(), config.isImageWatermark());
    }

    @Bean
    SpeechSynthesisService speechSynthesisService(DashScopeClient client, BotConfig config) {
        return new DashScopeSpeechSynthesisService(
            client, config.getTtsModel(), config.getTtsDefaultVoice(),
            config.getTtsFormat(), config.getTtsMaxTextLength());
    }

    @Bean
    PdfDocumentService pdfDocumentService() {
        return new PdfDocumentService();
    }

    @Bean
    WordDocumentService wordDocumentService() {
        return new WordDocumentService();
    }

    @Bean
    DocumentService documentService(PdfDocumentService pdf, WordDocumentService word) {
        DocumentService.silencePdfLogs();
        return new DocumentService(pdf, word);
    }

    @Bean
    MessageHandler imageMessageHandler(VisionService service) {
        return new ImageMessageHandler(service);
    }

    @Bean
    MessageHandler imageGenHandler(ImageGenService service) {
        return new ImageGenHandler(service);
    }

    @Bean
    MessageHandler documentMessageHandler(ChatService chat, DocumentService documents) {
        return new DocumentMessageHandler(chat, documents);
    }

    @Bean
    MessageHandler textMessageHandler(ChatService chat, SpeechSynthesisService speech,
                                      DocumentService documents, TianNewsTool news,
                                      BotConfig config) {
        SpeechSynthesisService optionalSpeech = config.isDashscopeConfigured() ? speech : null;
        return new TextMessageHandler(chat, optionalSpeech, documents, news);
    }
}
