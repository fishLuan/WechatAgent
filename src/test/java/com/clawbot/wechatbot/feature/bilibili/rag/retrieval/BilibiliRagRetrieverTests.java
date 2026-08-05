package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BilibiliRagRetrieverTests {
    @Test
    void retrievesRelevantChineseBigramInsteadOfUnrelatedHigherRatedContent() {
        BilibiliContentRepository repository = mock(BilibiliContentRepository.class);
        BilibiliContent relevant = content("治愈旅行", "温柔治愈的奇幻冒险", 8.8);
        BilibiliContent unrelated = content("硬核机甲", "未来战争与机械格斗", 9.9);
        when(repository.findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
            ContentType.BANGUMI, 0)).thenReturn(List.of(unrelated, relevant));

        List<BilibiliRagDocument> result = new BilibiliRagRetriever(repository)
            .retrieve("想看治愈冒险动漫", ContentType.BANGUMI, null, 5);

        assertEquals(List.of("治愈旅行"), result.stream()
            .map(BilibiliRagDocument::title).toList());
    }

    @Test
    void genericRecommendationCanFallBackToQualityOrdering() {
        BilibiliContentRepository repository = mock(BilibiliContentRepository.class);
        BilibiliContent high = content("高分作品", "剧情", 9.8);
        BilibiliContent low = content("普通作品", "剧情", 8.0);
        when(repository.findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
            ContentType.BANGUMI, 0)).thenReturn(List.of(high, low));

        List<BilibiliRagDocument> result = new BilibiliRagRetriever(repository)
            .retrieve("智能推荐动漫", ContentType.BANGUMI, null, 1);

        assertEquals("高分作品", result.getFirst().title());
    }

    private BilibiliContent content(String title, String description, double rating) {
        BilibiliContent content = new BilibiliContent(
            ContentType.BANGUMI, title, title);
        content.setDescription(description);
        content.setRating(rating);
        return content;
    }
}
