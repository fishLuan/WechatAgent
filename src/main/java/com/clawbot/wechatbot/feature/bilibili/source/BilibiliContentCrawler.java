package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 自动抓取 B 站候选内容并写入 bilibili_content 集合。 */
@Service
public class BilibiliContentCrawler {
    private final BilibiliContentSource contentSource;
    private final BilibiliContentRepository contentRepository;
    private final BilibiliProperties properties;

    public BilibiliContentCrawler(
        BilibiliContentSource contentSource,
        BilibiliContentRepository contentRepository,
        BilibiliProperties properties
    ) {
        this.contentSource = contentSource;
        this.contentRepository = contentRepository;
        this.properties = properties;
    }

    public CrawlResult crawlConfiguredCandidates() {
        CrawlStats stats = new CrawlStats();
        List<String> failures = new ArrayList<>();
        List<TypeCrawlResult> typeResults = new ArrayList<>();
        crawlOneType(ContentType.BANGUMI, stats, typeResults, failures);
        crawlOneType(ContentType.SERIES, stats, typeResults, failures);
        crawlOneType(ContentType.MOVIE, stats, typeResults, failures);
        return new CrawlResult(
            stats.candidateCount,
            stats.insertedCount,
            stats.updatedCount,
            typeResults,
            failures);
    }

    public List<BilibiliContent> crawlAndStore(ContentType contentType, int limit)
        throws Exception {
        return crawlAndStoreWithStats(contentType, limit).contents();
    }

    public StoredContents crawlAndStoreWithStats(ContentType contentType, int limit)
        throws Exception {
        if (contentType == null || limit < 1 || contentType == ContentType.UPLOADER) {
            return new StoredContents(List.of(), 0, 0, 0);
        }
        List<BilibiliContent> saved = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        List<BilibiliContent> candidates =
            contentSource.findCandidates(contentType, limit);
        for (BilibiliContent candidate : candidates) {
            StoredContent stored = saveSnapshot(candidate);
            saved.add(stored.content());
            if (stored.inserted()) inserted++;
            else updated++;
        }
        return new StoredContents(saved, candidates.size(), inserted, updated);
    }

    private void crawlOneType(
        ContentType contentType,
        CrawlStats stats,
        List<TypeCrawlResult> typeResults,
        List<String> failures
    ) {
        try {
            StoredContents stored = crawlAndStoreWithStats(
                contentType, properties.recommendationCount(contentType));
            stats.candidateCount += stored.candidateCount();
            stats.insertedCount += stored.insertedCount();
            stats.updatedCount += stored.updatedCount();
            typeResults.add(new TypeCrawlResult(
                contentType,
                stored.candidateCount(),
                stored.insertedCount(),
                stored.updatedCount()));
        } catch (Exception e) {
            failures.add(contentType + ": " + e.getMessage());
            typeResults.add(new TypeCrawlResult(contentType, 0, 0, 0));
        }
    }

    private StoredContent saveSnapshot(BilibiliContent incoming) {
        if (incoming == null
                || incoming.getContentType() == null
                || incoming.getContentId() == null
                || incoming.getContentId().isBlank()) {
            throw new IllegalArgumentException("B 站候选内容缺少 contentType 或 contentId");
        }
        Instant now = Instant.now();
        Optional<BilibiliContent> existing = contentRepository
            .findByContentTypeAndContentId(
                incoming.getContentType(), incoming.getContentId());
        boolean inserted = existing.isEmpty();
        BilibiliContent target = existing.orElse(incoming);
        if (inserted && target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }
        if (target != incoming) copyMutableFields(incoming, target);
        target.setUpdatedAt(now);
        target.setLastFetchedAt(
            incoming.getLastFetchedAt() == null ? now : incoming.getLastFetchedAt());
        return new StoredContent(contentRepository.save(target), inserted);
    }

    private void copyMutableFields(BilibiliContent source, BilibiliContent target) {
        target.setSeasonId(source.getSeasonId());
        target.setTitle(source.getTitle());
        target.setDescription(source.getDescription());
        target.setGenres(source.getGenres());
        target.setRating(source.getRating());
        target.setViewCount(source.getViewCount());
        target.setCoverUrl(source.getCoverUrl());
        target.setPageUrl(source.getPageUrl());
        target.setLatestEpisodeId(source.getLatestEpisodeId());
        target.setLatestEpisodeTitle(source.getLatestEpisodeTitle());
        target.setLatestEpisodeNumber(source.getLatestEpisodeNumber());
        target.setFinished(source.isFinished());
    }

    private static class CrawlStats {
        private int candidateCount;
        private int insertedCount;
        private int updatedCount;
    }

    public record StoredContent(BilibiliContent content, boolean inserted) {
    }

    public record StoredContents(
        List<BilibiliContent> contents,
        int candidateCount,
        int insertedCount,
        int updatedCount
    ) {
        public StoredContents {
            contents = contents == null ? List.of() : List.copyOf(contents);
        }
    }

    public record TypeCrawlResult(
        ContentType contentType,
        int candidateCount,
        int insertedCount,
        int updatedCount
    ) {
        public int savedCount() {
            return insertedCount + updatedCount;
        }
    }

    public record CrawlResult(
        int candidateCount,
        int insertedCount,
        int updatedCount,
        List<TypeCrawlResult> typeResults,
        List<String> failures
    ) {
        public CrawlResult {
            typeResults = typeResults == null ? List.of() : List.copyOf(typeResults);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public int savedCount() {
            return insertedCount + updatedCount;
        }

        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }
}
