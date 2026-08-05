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
import java.util.ArrayList;
import java.util.Comparator;
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
            System.out.println("[BILIBILI-SEARCH] keyword=" + query
                + " source=MONGO matches=" + local.size());
            return cache(cacheKey, local);
        }
        try {
            List<BilibiliContent> remote = remoteSource.searchByTitle(query, resultLimit);
            persist(remote);
            System.out.println("[BILIBILI-SEARCH] keyword=" + query
                + " source=WBI matches=" + remote.size() + " stored=" + remote.size());
            return cache(cacheKey, remote);
        } catch (Exception error) {
            throw new IllegalStateException(
                "本地没有匹配作品，B站在线搜索暂时受限，请稍后再试", error);
        }
    }

    private List<BilibiliContent> searchLocal(String title) {
        try {
            PageRequest page = PageRequest.of(
                0, resultLimit, Sort.by(Sort.Direction.DESC, "rating"));
            List<BilibiliContent> direct =
                contents.findByTitleContainingIgnoreCase(title, page);
            if (!direct.isEmpty()) return direct;
            String requested = normalizedTitle(title);
            List<BilibiliContent> similar = new ArrayList<>();
            for (BilibiliContent content : contents.findAll()) {
                String candidate = normalizedTitle(content.getTitle());
                if (candidate.contains(requested) || requested.contains(candidate)
                    || similarity(requested, candidate) >= 0.72) {
                    similar.add(content);
                }
            }
            similar.sort(Comparator
                .comparingDouble((BilibiliContent item) ->
                    similarity(requested, normalizedTitle(item.getTitle()))).reversed()
                .thenComparing(item -> item.getRating() == null ? 0.0 : item.getRating(),
                    Comparator.reverseOrder()));
            return similar.stream().limit(resultLimit).toList();
        } catch (Exception error) {
            System.err.println("[BILIBILI] 本地作品库搜索失败，尝试实时搜索："
                + error.getMessage());
            return List.of();
        }
    }

    private String normalizedTitle(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replace("的", "")
            .replaceAll("[\\s·:：,，。！？《》【】()（）\\-—_]", "");
    }

    private double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost);
            }
            previous = current;
        }
        return 1.0 - (double) previous[right.length()]
            / Math.max(left.length(), right.length());
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
