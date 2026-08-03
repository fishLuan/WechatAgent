package com.clawbot.wechatbot.config;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.handler.DocumentMessageHandler;
import com.clawbot.wechatbot.handler.ImageMessageHandler;
import com.clawbot.wechatbot.handler.TextMessageHandler;
import com.clawbot.wechatbot.intent.IntentRecognizer;
import com.clawbot.wechatbot.memory.ConversationMemoryService;
import com.clawbot.wechatbot.memory.MemoryProperties;
import com.clawbot.wechatbot.messaging.MessageDispatchCoordinator;
import com.clawbot.wechatbot.messaging.PerUserMessageDispatchCoordinator;
import com.clawbot.wechatbot.notification.DingTalkNotificationService;
import com.clawbot.wechatbot.notification.NoOpNotificationService;
import com.clawbot.wechatbot.notification.NotificationService;
import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.service.VisionService;
import com.clawbot.wechatbot.service.agent.AgentInputAttachmentLoader;
import com.clawbot.wechatbot.service.agent.AgentOrchestrator;
import com.clawbot.wechatbot.service.agent.AgentTaskHandler;
import com.clawbot.wechatbot.service.agent.DocumentAnalysisAgentTaskHandler;
import com.clawbot.wechatbot.service.client.DashScopeClient;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.document.PdfDocumentService;
import com.clawbot.wechatbot.service.document.WordDocumentService;
import com.clawbot.wechatbot.service.impl.DashScopeImageGenService;
import com.clawbot.wechatbot.service.impl.DashScopeSpeechSynthesisService;
import com.clawbot.wechatbot.service.impl.DashScopeVisionService;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.service.reply.LongReplyManager;
import com.clawbot.wechatbot.tools.tiannewstool.TianNewsTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 核心基础设施、多媒体服务和消息入口的装配。 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {"com.clawbot.wechatbot.scheduler"})
public class BotBeanConfiguration {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

    @Bean(destroyMethod = "close")
    MessageDispatchCoordinator messageDispatchCoordinator(BotConfig config) {
        return new PerUserMessageDispatchCoordinator(config.getMessageDispatchParallelism(),
            config.getMessageDispatchMaxPending(),
            Duration.ofSeconds(config.getMessageDispatchShutdownWaitSeconds()));
    }

    @Bean(destroyMethod = "close")
    NotificationService notificationService(BotConfig config, ObjectMapper mapper) {
        if (!config.isDingTalkNotificationConfigured()) return new NoOpNotificationService();
        return new DingTalkNotificationService(config.getDingTalkWebhook(),
            config.getDingTalkSecret(), config.getDingTalkTimeoutSeconds(),
            config.getDingTalkErrorDeduplicateSeconds(), mapper);
    }

    @Bean DeepSeekClient deepSeekClient(BotConfig config) {
        return new DeepSeekClient(config.getDeepSeekApiKey(), config.getDeepSeekModel(),
            config.getDeepSeekUrl(), config.getDeepSeekTemperature(),
            config.getDeepSeekMaxTokens(), config.getDeepSeekConnectTimeoutSeconds(),
            config.getDeepSeekRequestTimeoutSeconds());
    }

    @Bean DashScopeClient dashScopeClient(BotConfig config) {
        return new DashScopeClient(config.getDashscopeApiKey(), config.getDashscopeEndpoint(),
            config.getDashscopeConnectTimeoutSeconds(), config.getDashscopeRequestTimeoutSeconds());
    }

    @Bean VisionService visionService(DashScopeClient client, BotConfig config) {
        return new DashScopeVisionService(client, config.getVisionModel(),
            config.getVisionDefaultQuestion());
    }
    @Bean ImageGenService imageGenService(DashScopeClient client, BotConfig config) {
        return new DashScopeImageGenService(client, config.getImageModel(),
            config.getImageDefaultSize(), config.getImageDefaultCount(),
            config.isImagePromptExtend(), config.isImageWatermark());
    }
    @Bean SpeechSynthesisService speechSynthesisService(DashScopeClient client, BotConfig config) {
        return new DashScopeSpeechSynthesisService(client, config.getTtsModel(),
            config.getTtsDefaultVoice(), config.getTtsFormat(), config.getTtsMaxTextLength());
    }

    @Bean PdfDocumentService pdfDocumentService() { return new PdfDocumentService(); }
    @Bean WordDocumentService wordDocumentService() { return new WordDocumentService(); }
    @Bean DocumentService documentService(PdfDocumentService pdf, WordDocumentService word) {
        DocumentService.silencePdfLogs();
        return new DocumentService(pdf, word);
    }
    @Bean AgentInputAttachmentLoader agentInputAttachmentLoader(
        DocumentService documents, BotConfig config
    ) {
        return new AgentInputAttachmentLoader(documents,
            config.getAgentMaxInputAttachments(), config.getAgentMaxSingleInputBytes(),
            config.getAgentMaxTotalInputBytes());
    }
    @Bean AgentTaskHandler documentAnalysisAgentTaskHandler(
        DocumentService documents, DeepSeekChatService chat, BotConfig config
    ) {
        return new DocumentAnalysisAgentTaskHandler(
            documents, chat, config.getAgentMaxDocumentChars());
    }
    @Bean LongReplyManager longReplyManager(BotConfig config) {
        return new LongReplyManager(config.getLongReplyThreshold(),
            config.getLongReplyChunkSize(), config.getLongReplyMaxPendingChars(),
            Duration.ofMinutes(config.getLongReplyPendingExpireMinutes()));
    }

    @Bean MessageHandler imageMessageHandler(VisionService service) {
        return new ImageMessageHandler(service);
    }
    @Bean MessageHandler documentMessageHandler(
        DeepSeekChatService chat, DocumentService documents
    ) {
        return new DocumentMessageHandler(chat, documents);
    }
    @Bean MessageHandler textMessageHandler(
        DeepSeekChatService chat, AgentOrchestrator orchestrator,
        SpeechSynthesisService speech, DocumentService documents, TianNewsTool news,
        BotConfig config, ConversationMemoryService memory, MemoryProperties memoryProperties,
        LongReplyManager replies, IntentRecognizer intents,
        AgentInputAttachmentLoader attachments
    ) {
        SpeechSynthesisService optionalSpeech = config.isDashscopeConfigured() ? speech : null;
        return new TextMessageHandler(chat, orchestrator, optionalSpeech, documents, news,
            memory, memoryProperties, replies, intents, attachments);
    }
}
