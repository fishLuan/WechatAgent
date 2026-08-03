package com.clawbot.wechatbot.feature.bilibili.application;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Searches cached/local content before making a rate-limited Bilibili request. */
@Service
public final class BilibiliTitleSearchService {
    private final BilibiliContentRepository contents;
    private final BilibiliContentSource remoteSource;
    private final int resultLimit;
    private final Duration cacheTtl;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public BilibiliTitleSearchService(
        BilibiliContentRepository contents,
        BilibiliContentSource remoteSource,
        BilibiliProperties properties
    ) {
        this.contents = contents;
        this.remoteSource = remoteSource;
        this.resultLimit = properties.getSearchResultCount();
        this.cacheTtl = Duration.ofMinutes(properties.getSearchCacheMinutes());
    }

    public List<BilibiliContent> search(String title) throws Exception {
        if (title == null || title.isBlank()) return List.of();
        String query = title.trim();
        String cacheKey = query.toLowerCase(Locale.ROOT);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.expired(cacheTtl)) {
            return cached.results();
        }
        if (cached != null) cache.remove(cacheKey, cached);

        List<BilibiliContent> local = searchLocal(query);
        if (!local.isEmpty()) {
            return cache(cacheKey, local);
        }

        List<BilibiliContent> remote = remoteSource.searchByTitle(query, resultLimit);
        persist(remote);
        return cache(cacheKey, remote);
    }

    private List<BilibiliContent> searchLocal(String title) {
        try {
            PageRequest page = PageRequest.of(
                0, resultLimit, Sort.by(Sort.Direction.DESC, "rating"));
            return contents.findByTitleContainingIgnoreCase(title, page);
        } catch (Exception error) {
            System.err.println("[BILIBILI] 本地作品库搜索失败，尝试实时搜索："
                + error.getMessage());
            return List.of();
        }
    }

    private void persist(List<BilibiliContent> results) {
        if (results == null || results.isEmpty()) return;
        for (BilibiliContent content : results) {
            try {
                contents.findByContentTypeAndContentId(
                    content.getContentType(), content.getContentId())
                    .ifPresent(existing -> content.setId(existing.getId()));
                contents.save(content);
            } catch (Exception error) {
                System.err.println("[BILIBILI] 搜索结果写入本地库失败 contentId="
                    + content.getContentId() + "：" + error.getMessage());
            }
        }
    }

    private List<BilibiliContent> cache(
        String cacheKey, List<BilibiliContent> results
    ) {
        List<BilibiliContent> safeResults = results == null
            ? List.of() : List.copyOf(results);
        cache.put(cacheKey, new CacheEntry(Instant.now(), safeResults));
        return safeResults;
    }

    private record CacheEntry(Instant cachedAt, List<BilibiliContent> results) {
        boolean expired(Duration ttl) {
            return cachedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
