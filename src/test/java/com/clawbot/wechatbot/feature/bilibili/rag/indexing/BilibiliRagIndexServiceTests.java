package com.clawbot.wechatbot.feature.bilibili.rag.indexing;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.embedding.EmbeddingService;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliRagIndexServiceTests {

    @Test
    void indexesEnabledContent() throws Exception {
        BilibiliProperties properties = new BilibiliProperties();
        properties.getRag().getVector().setEnabled(true);
        EmbeddingService embeddingService = embeddingService();
        BilibiliRagVectorRepository repository = mock(BilibiliRagVectorRepository.class);
        BilibiliContent content = new BilibiliContent(
            ContentType.BANGUMI, "media-1", "葬送的芙莉莲");
        content.setDescription("治愈奇幻旅程");
        when(repository.findByContentTypeAndContentIdAndEmbeddingModelAndEmbeddingDimension(
            ContentType.BANGUMI, "media-1", "qwen3.7-text-embedding", 1024))
            .thenReturn(Optional.empty());

        BilibiliRagIndexService service = new BilibiliRagIndexService(
            properties,
            embeddingService,
            new BilibiliRagDocumentTextBuilder(),
            mock(BilibiliContentRepository.class),
            repository);

        assertTrue(service.index(content));
        verify(repository).save(any(BilibiliRagVectorDocument.class));
    }

    @Test
    void skipsUnchangedContentHash() throws Exception {
        BilibiliProperties properties = new BilibiliProperties();
        properties.getRag().getVector().setEnabled(true);
        EmbeddingService embeddingService = embeddingService();
        BilibiliRagVectorRepository repository = mock(BilibiliRagVectorRepository.class);
        BilibiliContent content = new BilibiliContent(
            ContentType.BANGUMI, "media-1", "葬送的芙莉莲");
        content.setDescription("治愈奇幻旅程");

        BilibiliRagIndexService service = new BilibiliRagIndexService(
            properties,
            embeddingService,
            new BilibiliRagDocumentTextBuilder(),
            mock(BilibiliContentRepository.class),
            repository);
        service.index(content);
        BilibiliRagVectorDocument existing = new BilibiliRagVectorDocument();
        existing.setContentHash(repositorySaveHash(repository));
        when(repository.findByContentTypeAndContentIdAndEmbeddingModelAndEmbeddingDimension(
            ContentType.BANGUMI, "media-1", "qwen3.7-text-embedding", 1024))
            .thenReturn(Optional.of(existing));

        assertFalse(service.index(content));
        verify(embeddingService).embedDocuments(List.of(
            new BilibiliRagDocumentTextBuilder().build(content)));
    }

    @Test
    void skipsWhenVectorDisabled() throws Exception {
        BilibiliProperties properties = new BilibiliProperties();
        EmbeddingService embeddingService = embeddingService();
        BilibiliRagVectorRepository repository = mock(BilibiliRagVectorRepository.class);

        BilibiliRagIndexService service = new BilibiliRagIndexService(
            properties,
            embeddingService,
            new BilibiliRagDocumentTextBuilder(),
            mock(BilibiliContentRepository.class),
            repository);

        assertFalse(service.index(new BilibiliContent(
            ContentType.BANGUMI, "media-1", "葬送的芙莉莲")));
        verify(repository, never()).save(any());
    }

    private EmbeddingService embeddingService() throws Exception {
        EmbeddingService service = mock(EmbeddingService.class);
        when(service.isConfigured()).thenReturn(true);
        when(service.model()).thenReturn("qwen3.7-text-embedding");
        when(service.dimension()).thenReturn(1024);
        when(service.embedDocuments(any())).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        return service;
    }

    private String repositorySaveHash(BilibiliRagVectorRepository repository) {
        return org.mockito.Mockito.mockingDetails(repository)
            .getInvocations()
            .stream()
            .filter(invocation -> invocation.getMethod().getName().equals("save"))
            .map(invocation -> (BilibiliRagVectorDocument) invocation.getArgument(0))
            .map(BilibiliRagVectorDocument::getContentHash)
            .findFirst()
            .orElseThrow();
    }
}
