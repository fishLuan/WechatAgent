package com.clawbot.wechatbot.intent;

import com.clawbot.wechatbot.tools.searchonlinetool.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One-shot, bounded web lookup used only to resolve an ambiguous work domain. */
@Component
public final class WebSearchContentDomainResolver {
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESULT_CHARS = 4_000;

    private final WebSearchTool webSearch;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CachedResolution> cache = new ConcurrentHashMap<>();

    public WebSearchContentDomainResolver(WebSearchTool webSearch, ObjectMapper mapper) {
        this.webSearch = webSearch;
        this.mapper = mapper;
    }

    public ContentDomainResolution resolve(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        if (title.isEmpty()) return unknown("缺少作品名称");
        String key = title.toLowerCase(Locale.ROOT);
        CachedResolution cached = cache.get(key);
        if (cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.resolution();
        }

        Future<ContentDomainResolution> future = executor.submit(() -> search(title));
        try {
            ContentDomainResolution resolution = future.get(
                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            cache.put(key, new CachedResolution(resolution, Instant.now()));
            return resolution;
        } catch (TimeoutException error) {
            future.cancel(true);
            return unknown("联网判断超过10秒");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return unknown("联网判断被中断");
        } catch (ExecutionException error) {
            return unknown("联网判断失败");
        }
    }

    private ContentDomainResolution search(String title) throws Exception {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("query", title + " 作品");
        arguments.put("count", 5);
        JsonNode root = mapper.readTree(webSearch.execute(arguments));
        if (!root.path("success").asBoolean(false)) {
            return unknown(root.path("error").asText("联网搜索无结果"));
        }

        int bookScore = 0;
        int videoScore = 0;
        StringBuilder evidence = new StringBuilder();
        JsonNode results = root.path("results");
        int limit = Math.min(5, results.isArray() ? results.size() : 0);
        for (int index = 0; index < limit && evidence.length() < MAX_RESULT_CHARS; index++) {
            JsonNode item = results.get(index);
            String text = (item.path("title").asText("") + " "
                + item.path("snippet").asText("") + " "
                + item.path("url").asText("")).toLowerCase(Locale.ROOT);
            bookScore += score(text, "小说", "图书", "书籍", "作者", "出版社",
                "出版", "isbn", "豆瓣读书", "weread", "book.douban");
            videoScore += score(text, "电影", "电视剧", "动画", "动漫", "番剧",
                "导演", "主演", "演员", "集数", "bilibili", "腾讯视频",
                "爱奇艺", "优酷", "movie.douban");
            evidence.append(item.path("title").asText("")).append("；");
        }
        String summary = evidence.length() > 300
            ? evidence.substring(0, 300) : evidence.toString();
        if (bookScore >= 2 && videoScore >= 2) {
            return new ContentDomainResolution(
                ContentDomainResolution.Domain.BOTH, confidence(bookScore, videoScore), summary);
        }
        if (bookScore >= 2) {
            return new ContentDomainResolution(
                ContentDomainResolution.Domain.BOOK, confidence(bookScore, videoScore), summary);
        }
        if (videoScore >= 2) {
            return new ContentDomainResolution(
                ContentDomainResolution.Domain.BILIBILI, confidence(videoScore, bookScore), summary);
        }
        return unknown(summary.isBlank() ? "搜索结果缺少领域特征" : summary);
    }

    private int score(String text, String... markers) {
        int result = 0;
        for (String marker : markers) if (text.contains(marker)) result++;
        return result;
    }

    private double confidence(int primary, int secondary) {
        return Math.min(0.98, 0.60 + 0.04 * primary + 0.01 * Math.abs(primary - secondary));
    }

    private ContentDomainResolution unknown(String evidence) {
        return new ContentDomainResolution(
            ContentDomainResolution.Domain.UNKNOWN, 0.0, evidence == null ? "" : evidence);
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }

    private record CachedResolution(
        ContentDomainResolution resolution, Instant createdAt
    ) {}
}
