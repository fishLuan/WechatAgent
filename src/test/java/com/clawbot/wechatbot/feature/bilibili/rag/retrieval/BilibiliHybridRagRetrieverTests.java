package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.embedding.EmbeddingService;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.vector.BilibiliRagVectorRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliHybridRagRetrieverTests {
    @Test
    void usesReferenceDocumentAsVectorQueryAndExcludesTheSeed() throws Exception {
        BilibiliProperties properties = new BilibiliProperties();
        properties.getRag().getVector().setEnabled(true);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.isConfigured()).thenReturn(true);
        when(embeddings.model()).thenReturn("test-model");
        when(embeddings.dimension()).thenReturn(2);
        when(embeddings.embedQuery(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of(1.0, 0.0));
        BilibiliRagVectorRepository vectors = mock(BilibiliRagVectorRepository.class);
        BilibiliContentRepository contents = mock(BilibiliContentRepository.class);
        BilibiliRagRetriever keywords = mock(BilibiliRagRetriever.class);

        BilibiliRagDocument seed = document("seed", "葬送的芙莉莲 中配版", "治愈奇幻旅程");
        BilibiliRagDocument dubbed = document("dubbed", "葬送的芙莉莲 国语版", "治愈冒险故事");
        BilibiliRagDocument keyword = document("keyword", "夏目友人帐", "温柔妖怪故事");
        when(keywords.retrieve(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(ContentType.BANGUMI),
            org.mockito.ArgumentMatchers.eq("葬送的芙莉莲"),
            org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of(seed, dubbed, keyword));

        BilibiliRagVectorDocument vector = new BilibiliRagVectorDocument();
        vector.setContentType(ContentType.BANGUMI);
        vector.setContentId("vector");
        vector.setEmbedding(List.of(1.0, 0.0));
        when(vectors.findByContentTypeAndEmbeddingModelAndEmbeddingDimension(
            ContentType.BANGUMI, "test-model", 2)).thenReturn(List.of(vector));
        BilibiliContent vectorContent = new BilibiliContent(
            ContentType.BANGUMI, "vector", "紫罗兰永恒花园");
        when(contents.findByContentTypeAndContentId(ContentType.BANGUMI, "vector"))
            .thenReturn(Optional.of(vectorContent));

        List<BilibiliRagDocument> result = new BilibiliHybridRagRetriever(
            properties, embeddings, vectors, contents, keywords)
            .retrieve("推荐类似作品", ContentType.BANGUMI, "葬送的芙莉莲", 3);

        verify(embeddings).embedQuery(argThat(query ->
            query.contains("葬送的芙莉莲") && query.contains("治愈")));
        assertEquals(List.of("紫罗兰永恒花园", "夏目友人帐"), result.stream()
            .map(BilibiliRagDocument::title).toList());
    }

    private BilibiliRagDocument document(String id, String title, String description) {
        return new BilibiliRagDocument(
            ContentType.BANGUMI, id, "season-" + id, title, description,
            Set.of("治愈"), 9.0, 100L, "https://example.com/" + id,
            "第1集", 1, false);
    }
}
