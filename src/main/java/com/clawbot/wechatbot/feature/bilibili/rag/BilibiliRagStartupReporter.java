package com.clawbot.wechatbot.feature.bilibili.rag;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.rag.embedding.EmbeddingService;
import com.clawbot.wechatbot.service.ChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class BilibiliRagStartupReporter implements ApplicationRunner {
    private final BilibiliProperties properties;
    private final EmbeddingService embeddingService;
    private final ObjectProvider<ChatService> chatService;

    public BilibiliRagStartupReporter(
        BilibiliProperties properties,
        EmbeddingService embeddingService,
        ObjectProvider<ChatService> chatService
    ) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.chatService = chatService;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean bilibiliEnabled = properties.isEnabled();
        boolean vectorEnabled = properties.getRag().getVector().isEnabled();
        boolean embeddingConfigured = embeddingService.isConfigured();
        ChatService generator = chatService.getIfAvailable();
        boolean generatorConfigured = generator != null && generator.isConfigured();

        System.out.printf(
            "[BILIBILI-RAG] 启动状态：bilibili=%s, vector=%s, embeddingConfigured=%s, "
                + "embeddingModel=%s, embeddingDimension=%d, generatorConfigured=%s%n",
            bilibiliEnabled,
            vectorEnabled,
            embeddingConfigured,
            embeddingService.model(),
            embeddingService.dimension(),
            generatorConfigured);

        if (!bilibiliEnabled) {
            System.out.println("[BILIBILI-RAG] B站能力未启用；请设置 BILIBILI_ENABLED=true。");
        } else if (vectorEnabled && !embeddingConfigured) {
            System.out.println("[BILIBILI-RAG] 向量检索已启用但 Embedding 未配置，将无法建立向量索引。");
        }
        if (bilibiliEnabled && !generatorConfigured) {
            System.out.println("[BILIBILI-RAG] 生成模型未配置，RAG 将使用检索结果的降级回答。");
        }
    }
}
