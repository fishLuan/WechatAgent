package com.clawbot.wechatbot.config;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.handler.DocumentMessageHandler;
import com.clawbot.wechatbot.handler.ImageGenHandler;
import com.clawbot.wechatbot.handler.ImageMessageHandler;
import com.clawbot.wechatbot.handler.TextMessageHandler;
import com.clawbot.wechatbot.memory.ConversationMemoryService;
import com.clawbot.wechatbot.memory.MemoryProperties;
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
import com.clawbot.wechatbot.service.multitask.LlmTaskPlanner;
import com.clawbot.wechatbot.service.multitask.MultiTaskChatService;
import com.clawbot.wechatbot.service.multitask.TaskPlanner;
import com.clawbot.wechatbot.service.reply.LongReplyManager;
import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.tools.UrlSafetyCheckerTool.UrlSafetyChecker;
import com.clawbot.wechatbot.tools.bazitool.BaziFortuneTool;
import com.clawbot.wechatbot.tools.exchangeratetool.ExchangeRateTool;
import com.clawbot.wechatbot.tools.idcardtool.IdCardTool;
import com.clawbot.wechatbot.tools.searchonlinetool.WebSearchTool;
import com.clawbot.wechatbot.tools.searchweathertool.AmapWeatherTool;
import com.clawbot.wechatbot.tools.tiannewstool.TianNewsTool;
import com.clawbot.wechatbot.tools.currenttimetool.CurrentTimeTool;
import com.clawbot.wechatbot.tools.webaccess.SafeHttpFetcher;
import com.clawbot.wechatbot.tools.webaccess.UrlAccessPolicy;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractClient;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    BaziFortuneTool baziFortuneTool(ObjectMapper mapper) {
        return new BaziFortuneTool(mapper);
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
    UrlAccessPolicy urlAccessPolicy(BotConfig config) {
        Set<Integer> allowedPorts = Arrays.stream(
                config.getWebPageExtractAllowedPorts().split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(Integer::parseInt)
            .collect(Collectors.toUnmodifiableSet());
        return new UrlAccessPolicy(allowedPorts);
    }

    @Bean
    SafeHttpFetcher safeHttpFetcher(BotConfig config, UrlAccessPolicy accessPolicy) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(
                config.getWebPageExtractConnectTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return new SafeHttpFetcher(
            http,
            accessPolicy,
            Duration.ofSeconds(config.getWebPageExtractRequestTimeoutSeconds()),
            config.getWebPageExtractMaxResponseBytes(),
            config.getWebPageExtractMaxRedirects(),
            "ClawBot-SafeHttpFetcher/1.0"
        );
    }

    @Bean
    WebPageExtractTool webPageExtractTool(
        BotConfig config, SafeHttpFetcher fetcher, ObjectMapper mapper
    ) {
        WebPageExtractClient client =
            new WebPageExtractClient(fetcher, config.getWebPageExtractMaxBodyChars());
        return new WebPageExtractTool(
            client, mapper, config.getWebPageExtractMaxBodyChars());
    }

    @Bean
    UrlSafetyChecker urlSafetyChecker(
        ObjectMapper mapper, SafeHttpFetcher safeHttpFetcher
    ) {
        return new UrlSafetyChecker(mapper, safeHttpFetcher);
    }

    @Bean
    CurrentTimeTool currentTimeTool(ObjectMapper mapper) {
        return new CurrentTimeTool(mapper);
    }

    @Bean
    IdCardTool idCardTool(ObjectMapper mapper) {
        return new IdCardTool(mapper);
    }

    @Bean
    FunctionToolRegistry functionToolRegistry(ObjectMapper mapper, List<FunctionTool> tools) {
        return new FunctionToolRegistry(mapper, tools);
    }

    @Bean
    DeepSeekChatService singleTaskChatService(
        DeepSeekClient client, FunctionToolRegistry registry, BotConfig config) {
        return new DeepSeekChatService(
            client, registry, config.getSystemPrompt(), config.getDeepSeekMaxToolRounds());
    }

    @Bean
    TaskPlanner taskPlanner(DeepSeekClient client, BotConfig config) {
        return new LlmTaskPlanner(client, config.getDeepSeekMultiTaskMaxTasks());
    }

    @Bean(destroyMethod = "close")
    @Primary
    ChatService chatService(DeepSeekChatService singleTaskChatService,
                            TaskPlanner taskPlanner, BotConfig config) {
        return new MultiTaskChatService(
            singleTaskChatService,
            taskPlanner,
            config.isDeepSeekMultiTaskEnabled(),
            config.getDeepSeekMultiTaskMaxParallelism());
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
    LongReplyManager longReplyManager(BotConfig config) {
        return new LongReplyManager(
            config.getLongReplyThreshold(),
            config.getLongReplyChunkSize(),
            config.getLongReplyMaxPendingChars(),
            Duration.ofMinutes(config.getLongReplyPendingExpireMinutes()));
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
    MessageHandler documentMessageHandler(DeepSeekChatService singleTaskChatService,
                                          DocumentService documents) {
        return new DocumentMessageHandler(singleTaskChatService, documents);
    }

    @Bean
    MessageHandler textMessageHandler(ChatService chat, SpeechSynthesisService speech,
                                      DocumentService documents, TianNewsTool news,
                                      BotConfig config,
                                      ConversationMemoryService memoryService,
                                      MemoryProperties memoryProperties,
                                      LongReplyManager longReplyManager) {
        SpeechSynthesisService optionalSpeech = config.isDashscopeConfigured() ? speech : null;
        return new TextMessageHandler(
            chat,
            optionalSpeech,
            documents,
            news,
            memoryService,
            memoryProperties,
            longReplyManager
        );
    }
}
