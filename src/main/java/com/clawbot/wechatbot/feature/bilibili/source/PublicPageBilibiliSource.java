package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.source.client.BilibiliHttpClient;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliContentDto;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliEpisodeDto;
import com.clawbot.wechatbot.feature.bilibili.source.parser.BilibiliPageParser;
import com.clawbot.wechatbot.feature.bilibili.source.parser.BilibiliUrlParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 基于 B 站公开页面和公开 Web API 的内容采集实现。 */
@Service
public class PublicPageBilibiliSource implements BilibiliContentSource {
    private static final String PGC_BY_SEASON =
        "https://api.bilibili.com/pgc/view/web/season?season_id=%s";
    private static final String PGC_BY_EPISODE =
        "https://api.bilibili.com/pgc/view/web/season?ep_id=%s";
    private static final String PGC_BY_MEDIA =
        "https://api.bilibili.com/pgc/review/user?media_id=%s";
    private static final String VIDEO_BY_BVID =
        "https://api.bilibili.com/x/web-interface/view?bvid=%s";
    private static final String VIDEO_BY_AVID =
        "https://api.bilibili.com/x/web-interface/view?aid=%s";
    private static final String UPLOADER_BY_MID =
        "https://api.bilibili.com/x/web-interface/card?mid=%s";
    private static final String PGC_INDEX =
        "https://api.bilibili.com/pgc/season/index/result?"
            + "season_type=%d&type=1&st=1&sort=0&page=%d&pagesize=%d";
    private static final String SEARCH =
        "https://api.bilibili.com/x/web-interface/search/type?search_type=%s&keyword=%s&page_size=%d";

    private final BilibiliHttpClient httpClient;
    private final BilibiliUrlParser urlParser;
    private final BilibiliPageParser pageParser;
    private final Map<ContentType, Integer> nextPgcIndexPage =
        new EnumMap<>(ContentType.class);

    @Autowired
    public PublicPageBilibiliSource(BilibiliHttpClient httpClient) {
        this(httpClient, new BilibiliUrlParser(), new BilibiliPageParser());
    }

    PublicPageBilibiliSource(
        BilibiliHttpClient httpClient,
        BilibiliUrlParser urlParser,
        BilibiliPageParser pageParser
    ) {
        this.httpClient = httpClient;
        this.urlParser = urlParser;
        this.pageParser = pageParser;
    }

    @Override
    public BilibiliContent resolveUrl(String bilibiliUrl) throws Exception {
        String sourceUrl = expandShortUrlIfNeeded(bilibiliUrl);
        BilibiliUrlParser.ParsedBilibiliUrl parsed = urlParser.parse(sourceUrl)
            .orElseThrow(() -> new IllegalArgumentException("无法识别 B 站链接"));
        Optional<BilibiliContentDto> dto = switch (parsed.idType()) {
            case "season" -> fetchPgc(String.format(PGC_BY_SEASON,
                normalizeSeasonId(parsed.contentId())), parsed.normalizedUrl());
            case "episode" -> fetchPgc(String.format(PGC_BY_EPISODE,
                normalizeSeasonId(parsed.contentId())), parsed.normalizedUrl());
            case "media" -> fetchMedia(String.format(PGC_BY_MEDIA, parsed.contentId()),
                parsed.normalizedUrl());
            case "bvid" -> fetchVideo(String.format(VIDEO_BY_BVID, parsed.contentId()),
                parsed.normalizedUrl());
            case "avid" -> fetchVideo(String.format(VIDEO_BY_AVID, parsed.contentId()),
                parsed.normalizedUrl());
            case "mid" -> fetchUploader(String.format(UPLOADER_BY_MID, parsed.contentId()),
                parsed.normalizedUrl());
            case "live" -> throw new UnsupportedOperationException(
                "直播间链接暂不支持作品订阅或推荐");
            case "dynamic" -> throw new UnsupportedOperationException(
                "动态链接暂不支持作品订阅或推荐");
            default -> Optional.empty();
        };
        return dto.map(this::toContent)
            .orElseThrow(() -> new IllegalStateException("未能获取 B 站作品信息"));
    }

    @Override
    public Optional<BilibiliContent> findByContentId(
        ContentType contentType, String contentId) throws Exception {
        if (contentType == null || contentId == null || contentId.isBlank()) {
            return Optional.empty();
        }
        if (contentType == ContentType.BANGUMI
                || contentType == ContentType.MOVIE
                || contentType == ContentType.SERIES) {
            String seasonId = normalizeSeasonId(contentId);
            return fetchPgc(String.format(PGC_BY_SEASON, seasonId), "")
                .map(this::toContent)
                .map(c -> { c.setContentType(contentType); return c; });
        }
        if (contentType == ContentType.UPLOADER) {
            return fetchUploader(String.format(UPLOADER_BY_MID, contentId), "").map(this::toContent);
        }
        return Optional.empty();
    }

    private String normalizeSeasonId(String id) {
        if (id == null || id.isBlank()) return id;
        String trimmed = id.trim();
        if (trimmed.startsWith("ss")) return trimmed.substring(2);
        return trimmed;
    }

    @Override
    public List<BilibiliContent> findCandidates(ContentType contentType, int limit)
        throws Exception {
        if (limit < 1 || contentType == null || contentType == ContentType.UPLOADER) {
            return List.of();
        }
        List<BilibiliContent> indexCandidates = tryFindPgcIndexCandidates(contentType, limit);
        if (!indexCandidates.isEmpty()) return indexCandidates;

        String keyword = contentType == ContentType.MOVIE ? "高分电影" : "高分动漫";
        String searchType = contentType == ContentType.MOVIE ? "media_ft" : "media_bangumi";
        String url = String.format(
            SEARCH,
            searchType,
            URLEncoder.encode(keyword, StandardCharsets.UTF_8),
            Math.min(limit, 20));
        List<BilibiliContent> contents = new ArrayList<>();
        String body = httpClient.getAnonymousSearchText(url);
        if (body == null || body.isBlank()) return List.of();
        for (BilibiliContentDto dto : pageParser.parseSearchMediaJson(body, "")) {
            BilibiliContent content = toContent(dto);
            if (content.getContentType() == contentType) contents.add(content);
            if (contents.size() >= limit) break;
        }
        return contents;
    }

    private List<BilibiliContent> tryFindPgcIndexCandidates(
        ContentType contentType,
        int limit
    ) {
        try {
            return findPgcIndexCandidates(contentType, limit);
        } catch (Exception e) {
            System.err.println("[BILIBILI] PGC 索引候选池失败，降级搜索接口: "
                + e.getMessage());
            return List.of();
        }
    }

    private List<BilibiliContent> findPgcIndexCandidates(
        ContentType contentType,
        int limit
    ) throws Exception {
        Integer seasonType = pgcSeasonType(contentType);
        if (seasonType == null) return List.of();
        int page = nextPgcIndexPage(contentType);
        String body = httpClient.getText(String.format(
            PGC_INDEX, seasonType, page, Math.min(Math.max(limit, 1), 20)));
        ensureSupportedResponse(body);
        List<BilibiliContent> contents = new ArrayList<>();
        for (BilibiliContentDto dto : pageParser.parsePgcIndexJson(body, contentType)) {
            BilibiliContent content = toContent(dto);
            if (content.getContentType() == contentType) contents.add(content);
            if (contents.size() >= limit) break;
        }
        advancePgcIndexPage(contentType, contents.isEmpty());
        return contents;
    }

    private synchronized int nextPgcIndexPage(ContentType contentType) {
        return nextPgcIndexPage.getOrDefault(contentType, 1);
    }

    private synchronized void advancePgcIndexPage(
        ContentType contentType,
        boolean reset
    ) {
        int current = nextPgcIndexPage.getOrDefault(contentType, 1);
        nextPgcIndexPage.put(contentType, reset ? 1 : current + 1);
    }

    private Integer pgcSeasonType(ContentType contentType) {
        if (contentType == ContentType.BANGUMI) return 1;
        if (contentType == ContentType.MOVIE) return 2;
        if (contentType == ContentType.SERIES) return 5;
        return null;
    }

    @Override
    public BilibiliContent refresh(BilibiliContent content) throws Exception {
        if (content == null) throw new IllegalArgumentException("content 不能为空");
        String id = content.getSeasonId() == null || content.getSeasonId().isBlank()
            ? content.getContentId()
            : content.getSeasonId();
        return findByContentId(content.getContentType(), id).orElse(content);
    }

    private Optional<BilibiliContentDto> fetchPgc(String apiUrl, String pageUrl)
        throws Exception {
        String body = httpClient.getText(apiUrl);
        ensureSupportedResponse(body);
        return pageParser.parsePgcJson(body, pageUrl)
            .or(() -> pageParser.parseInitialState(body, pageUrl));
    }

    private Optional<BilibiliContentDto> fetchVideo(String apiUrl, String pageUrl)
        throws Exception {
        String body = httpClient.getText(apiUrl);
        ensureSupportedResponse(body);
        return pageParser.parseVideoJson(body, pageUrl);
    }

    private Optional<BilibiliContentDto> fetchMedia(String apiUrl, String pageUrl)
        throws Exception {
        String body = httpClient.getText(apiUrl);
        ensureSupportedResponse(body);
        return pageParser.parseMediaJson(body, pageUrl);
    }

    private Optional<BilibiliContentDto> fetchUploader(String apiUrl, String pageUrl)
        throws Exception {
        String body = httpClient.getText(apiUrl);
        ensureSupportedResponse(body);
        return pageParser.parseUploaderJson(body, pageUrl);
    }

    private void ensureSupportedResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("B 站接口返回为空");
        }
        if (body.contains("\"code\":-404")) {
            throw new IllegalArgumentException("该 B 站内容不存在或已下架");
        }
        if (body.contains("\"code\":-412")
                || body.contains("\"code\":-352")
                || body.contains("\"code\":-403")) {
            throw new IllegalStateException("B 站公开接口触发访问限制，请稍后再试");
        }
    }

    private String expandShortUrlIfNeeded(String bilibiliUrl) throws Exception {
        if (bilibiliUrl == null) return null;
        String trimmed = bilibiliUrl.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("https://b23.tv/")
                || lower.startsWith("http://b23.tv/")
                || lower.startsWith("b23.tv/")) {
            return httpClient.resolveFinalUrl(trimmed.startsWith("http")
                ? trimmed
                : "https://" + trimmed);
        }
        return bilibiliUrl;
    }

    private BilibiliContent toContent(BilibiliContentDto dto) {
        BilibiliContent content = new BilibiliContent(
            dto.getContentType(), dto.getContentId(), dto.getTitle());
        content.setSeasonId(normalizeSeasonId(dto.getSeasonId()));
        content.setDescription(dto.getDescription());
        content.setGenres(dto.getGenres());
        content.setRating(dto.getRating());
        content.setViewCount(dto.getViewCount());
        content.setCoverUrl(dto.getCoverUrl());
        content.setPageUrl(dto.getPageUrl());
        BilibiliEpisodeDto latest = dto.getLatestEpisode();
        if (latest != null) {
            content.setLatestEpisodeId(latest.episodeId());
            content.setLatestEpisodeTitle(latest.title());
            content.setLatestEpisodeNumber(latest.episodeNumber());
        }
        content.setFinished(dto.isFinished());
        content.setLastFetchedAt(Instant.now());
        return content;
    }
}