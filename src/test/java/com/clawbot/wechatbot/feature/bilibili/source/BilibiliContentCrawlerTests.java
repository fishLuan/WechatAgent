package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliContentCrawlerTests {

    @Test
    void crawlsCandidatesAndSavesNewContent() throws Exception {
        BilibiliContentSource source = mock(BilibiliContentSource.class);
        BilibiliContentRepository repository = mock(BilibiliContentRepository.class);
        BilibiliContent candidate =
            new BilibiliContent(ContentType.BANGUMI, "media-1", "测试番剧");
        when(source.findCandidates(ContentType.BANGUMI, 2)).thenReturn(List.of(candidate));
        when(repository.findByContentTypeAndContentId(ContentType.BANGUMI, "media-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any(BilibiliContent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        BilibiliContentCrawler crawler = new BilibiliContentCrawler(
            source,
            repository,
            new BilibiliProperties());

        List<BilibiliContent> saved = crawler.crawlAndStore(ContentType.BANGUMI, 2);

        assertEquals(1, saved.size());
        assertEquals("测试番剧", saved.get(0).getTitle());
        assertTrue(saved.get(0).getUpdatedAt() != null);
        assertTrue(saved.get(0).getLastFetchedAt() != null);
        verify(repository).save(candidate);
    }

    @Test
    void updatesExistingContentWithoutChangingIdentityOrCreatedAt() throws Exception {
        BilibiliContentSource source = mock(BilibiliContentSource.class);
        BilibiliContentRepository repository = mock(BilibiliContentRepository.class);
        BilibiliContent existing =
            new BilibiliContent(ContentType.MOVIE, "movie-1", "旧标题");
        existing.setId("mongo-id");
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        existing.setCreatedAt(createdAt);
        BilibiliContent incoming =
            new BilibiliContent(ContentType.MOVIE, "movie-1", "新标题");
        incoming.setRating(9.2);
        when(source.findCandidates(ContentType.MOVIE, 1)).thenReturn(List.of(incoming));
        when(repository.findByContentTypeAndContentId(ContentType.MOVIE, "movie-1"))
            .thenReturn(Optional.of(existing));
        when(repository.save(any(BilibiliContent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        BilibiliContentCrawler crawler = new BilibiliContentCrawler(
            source,
            repository,
            new BilibiliProperties());

        List<BilibiliContent> saved = crawler.crawlAndStore(ContentType.MOVIE, 1);

        assertSame(existing, saved.get(0));
        assertEquals("mongo-id", existing.getId());
        assertEquals(createdAt, existing.getCreatedAt());
        assertEquals("新标题", existing.getTitle());
        assertEquals(9.2, existing.getRating());
    }

    @Test
    void recordsFailuresWithoutStoppingOtherContentTypes() throws Exception {
        BilibiliContentSource source = mock(BilibiliContentSource.class);
        BilibiliContentRepository repository = mock(BilibiliContentRepository.class);
        when(source.findCandidates(eq(ContentType.BANGUMI), anyInt()))
            .thenThrow(new IllegalStateException("blocked"));
        BilibiliContent movie =
            new BilibiliContent(ContentType.MOVIE, "movie-1", "测试电影");
        when(source.findCandidates(eq(ContentType.MOVIE), anyInt()))
            .thenReturn(List.of(movie));
        when(source.findCandidates(eq(ContentType.SERIES), anyInt()))
            .thenReturn(List.of());
        when(repository.findByContentTypeAndContentId(ContentType.MOVIE, "movie-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any(BilibiliContent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        BilibiliContentCrawler crawler = new BilibiliContentCrawler(
            source,
            repository,
            new BilibiliProperties());

        BilibiliContentCrawler.CrawlResult result =
            crawler.crawlConfiguredCandidates();

        assertEquals(1, result.savedCount());
        assertEquals(1, result.insertedCount());
        assertEquals(0, result.updatedCount());
        assertEquals(3, result.typeResults().size());
        assertFalse(result.failures().isEmpty());
        assertTrue(result.failures().get(0).contains("BANGUMI"));
    }
}
