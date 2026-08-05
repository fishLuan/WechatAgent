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
import java.util.Objects;

/** 自动抓取 B 站候选内容并写入 bilibili_content 集合。 */
@Service
public class BilibiliContentCrawler {
    private static final int DETAIL_ENRICHMENT_LIMIT_PER_TYPE = 2;
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
            stats.unchangedCount,
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
            return new StoredContents(List.of(), 0, 0, 0, 0);
        }
        List<BilibiliContent> saved = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        List<BilibiliContent> candidates =
            contentSource.findCandidates(contentType, limit);
        int enriched = 0;
        for (BilibiliContent candidate : candidates) {
            if (enriched < DETAIL_ENRICHMENT_LIMIT_PER_TYPE
                && enrichNewCandidate(candidate)) {
                enriched++;
            }
            StoredContent stored = saveSnapshot(candidate);
            saved.add(stored.content());
            if (stored.inserted()) inserted++;
            else if (stored.changed()) updated++;
            else unchanged++;
        }
        return new StoredContents(
            saved, candidates.size(), inserted, updated, unchanged);
    }

    /** 保存用户在线搜索发现的作品，并复用候选池的幂等更新规则。 */
    public List<BilibiliContent> storeDiscovered(List<BilibiliContent> contents) {
        if (contents == null || contents.isEmpty()) return List.of();
        List<BilibiliContent> saved = new ArrayList<>();
        for (BilibiliContent content : contents) {
            if (content == null || content.getContentType() == ContentType.UPLOADER) continue;
            saved.add(saveSnapshot(content).content());
        }
        return saved;
    }

    private boolean enrichNewCandidate(BilibiliContent candidate) {
        if (candidate == null || candidate.getSeasonId() == null
            || candidate.getSeasonId().isBlank()) return false;
        boolean exists = contentRepository.findByContentTypeAndContentId(
            candidate.getContentType(), candidate.getContentId()).isPresent();
        if (exists) return false;
        try {
            Optional<BilibiliContent> detail = Optional.ofNullable(
                contentSource.findBySeasonId(
                    candidate.getContentType(), candidate.getSeasonId()))
                .orElseGet(Optional::empty);
            detail.ifPresent(value -> mergeDetail(value, candidate));
            return detail.isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void mergeDetail(BilibiliContent detail, BilibiliContent target) {
        if (detail.getDescription() != null && !detail.getDescription().isBlank())
            target.setDescription(detail.getDescription());
        if (!detail.getGenres().isEmpty()) target.setGenres(detail.getGenres());
        if (target.getRating() == null) target.setRating(detail.getRating());
        if (target.getCoverUrl() == null || target.getCoverUrl().isBlank())
            target.setCoverUrl(detail.getCoverUrl());
        if (target.getPageUrl() == null || target.getPageUrl().isBlank())
            target.setPageUrl(detail.getPageUrl());
        if (detail.getLatestEpisodeId() != null)
            target.setLatestEpisodeId(detail.getLatestEpisodeId());
        if (detail.getLatestEpisodePubTime() != null)
            target.setLatestEpisodePubTime(detail.getLatestEpisodePubTime());
    }

    private void crawlOneType(
        ContentType contentType,
        CrawlStats stats,
        List<TypeCrawlResult> typeResults,
        List<String> failures
    ) {
        try {
            StoredContents stored = crawlAndStoreWithStats(
                contentType, properties.getCandidateCrawlBatchSize());
            stats.candidateCount += stored.candidateCount();
            stats.insertedCount += stored.insertedCount();
            stats.updatedCount += stored.updatedCount();
            stats.unchangedCount += stored.unchangedCount();
            typeResults.add(new TypeCrawlResult(
                contentType,
                stored.candidateCount(),
                stored.insertedCount(),
                stored.updatedCount(),
                stored.unchangedCount()));
        } catch (Exception e) {
            failures.add(contentType + ": " + e.getMessage());
            typeResults.add(new TypeCrawlResult(contentType, 0, 0, 0, 0));
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
        boolean changed = inserted || hasMutableChanges(incoming, target);
        if (inserted && target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }
        if (target != incoming) copyMutableFields(incoming, target);
        if (changed) target.setUpdatedAt(now);
        target.setLastFetchedAt(
            incoming.getLastFetchedAt() == null ? now : incoming.getLastFetchedAt());
        return new StoredContent(
            contentRepository.save(target), inserted, changed);
    }

    private boolean hasMutableChanges(
        BilibiliContent source, BilibiliContent target
    ) {
        return !Objects.equals(source.getSeasonId(), target.getSeasonId())
            || !Objects.equals(source.getTitle(), target.getTitle())
            || !Objects.equals(source.getDescription(), target.getDescription())
            || !Objects.equals(source.getGenres(), target.getGenres())
            || !Objects.equals(source.getRating(), target.getRating())
            || !Objects.equals(source.getViewCount(), target.getViewCount())
            || !Objects.equals(source.getCoverUrl(), target.getCoverUrl())
            || !Objects.equals(source.getPageUrl(), target.getPageUrl())
            || !Objects.equals(source.getLatestEpisodeId(), target.getLatestEpisodeId())
            || !Objects.equals(source.getLatestEpisodeTitle(), target.getLatestEpisodeTitle())
            || !Objects.equals(source.getLatestEpisodeNumber(), target.getLatestEpisodeNumber())
            || !Objects.equals(source.getLatestEpisodePubTime(), target.getLatestEpisodePubTime())
            || source.isFinished() != target.isFinished();
    }

    private void copyMutableFields(BilibiliContent source, BilibiliContent target) {
        target.setSeasonId(source.getSeasonId());
        target.setTitle(source.getTitle());
        if (source.getDescription() != null && !source.getDescription().isBlank())
            target.setDescription(source.getDescription());
        if (!source.getGenres().isEmpty()) target.setGenres(source.getGenres());
        if (source.getRating() != null) target.setRating(source.getRating());
        if (source.getViewCount() != null) target.setViewCount(source.getViewCount());
        if (source.getCoverUrl() != null && !source.getCoverUrl().isBlank())
            target.setCoverUrl(source.getCoverUrl());
        if (source.getPageUrl() != null && !source.getPageUrl().isBlank())
            target.setPageUrl(source.getPageUrl());
        if (source.getLatestEpisodeId() != null)
            target.setLatestEpisodeId(source.getLatestEpisodeId());
        if (source.getLatestEpisodeTitle() != null
            && !source.getLatestEpisodeTitle().isBlank()) {
            target.setLatestEpisodeTitle(source.getLatestEpisodeTitle());
            target.setFinished(source.isFinished());
        }
        if (source.getLatestEpisodeNumber() != null)
            target.setLatestEpisodeNumber(source.getLatestEpisodeNumber());
        if (source.getLatestEpisodePubTime() != null)
            target.setLatestEpisodePubTime(source.getLatestEpisodePubTime());
    }

    private static class CrawlStats {
        private int candidateCount;
        private int insertedCount;
        private int updatedCount;
        private int unchangedCount;
    }

    public record StoredContent(
        BilibiliContent content, boolean inserted, boolean changed
    ) {
    }

    public record StoredContents(
        List<BilibiliContent> contents,
        int candidateCount,
        int insertedCount,
        int updatedCount,
        int unchangedCount
    ) {
        public StoredContents {
            contents = contents == null ? List.of() : List.copyOf(contents);
        }
    }

    public record TypeCrawlResult(
        ContentType contentType,
        int candidateCount,
        int insertedCount,
        int updatedCount,
        int unchangedCount
    ) {
        public int savedCount() {
            return insertedCount + updatedCount + unchangedCount;
        }
    }

    public record CrawlResult(
        int candidateCount,
        int insertedCount,
        int updatedCount,
        int unchangedCount,
        List<TypeCrawlResult> typeResults,
        List<String> failures
    ) {
        public CrawlResult {
            typeResults = typeResults == null ? List.of() : List.copyOf(typeResults);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public int savedCount() {
            return insertedCount + updatedCount + unchangedCount;
        }

        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }
}
