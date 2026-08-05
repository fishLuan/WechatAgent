package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliCrawlState;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliCrawlStateRepository;
import com.clawbot.wechatbot.feature.bilibili.source.client.BilibiliHttpClient;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliContentDto;
import com.clawbot.wechatbot.feature.bilibili.source.parser.BilibiliPageParser;
import com.clawbot.wechatbot.feature.bilibili.source.parser.BilibiliUrlParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
        "https://api.bilibili.com/x/web-interface/search/type?search_type=%s&keyword=%s&page=1";
    private static final String PGC_TIMELINE =
        "https://api.bilibili.com/pgc/web/timeline?types=%d&before=6&after=0";

    private final BilibiliHttpClient httpClient;
    private final BilibiliUrlParser urlParser;
    private final BilibiliPageParser pageParser;
    private final ObjectMapper objectMapper;
    private final BilibiliContentMapper contentMapper;
    private final BilibiliCrawlStateRepository crawlStates;
    private final Map<ContentType, Integer> nextPgcIndexPage =
        new EnumMap<>(ContentType.class);

    @Autowired
    public PublicPageBilibiliSource(
        BilibiliHttpClient httpClient,
        ObjectMapper objectMapper,
        BilibiliCrawlStateRepository crawlStates
    ) {
        this(httpClient, new BilibiliUrlParser(), new BilibiliPageParser(),
            objectMapper, crawlStates);
    }

    PublicPageBilibiliSource(
        BilibiliHttpClient httpClient,
        BilibiliUrlParser urlParser,
        BilibiliPageParser pageParser,
        ObjectMapper objectMapper
    ) {
        this(httpClient, urlParser, pageParser, objectMapper, null);
    }

    PublicPageBilibiliSource(
        BilibiliHttpClient httpClient,
        BilibiliUrlParser urlParser,
        BilibiliPageParser pageParser,
        ObjectMapper objectMapper,
        BilibiliCrawlStateRepository crawlStates
    ) {
        this.httpClient = httpClient;
        this.urlParser = urlParser;
        this.pageParser = pageParser;
        this.objectMapper = objectMapper;
        this.contentMapper = new BilibiliContentMapper();
        this.crawlStates = crawlStates;
    }

    @Override
    public BilibiliContent resolveUrl(String bilibiliUrl) throws Exception {
        String sourceUrl = expandShortUrlIfNeeded(bilibiliUrl);
        BilibiliUrlParser.ParsedBilibiliUrl parsed = urlParser.parse(sourceUrl)
            .orElseThrow(() -> new IllegalArgumentException("无法识别 B 站链接"));
        Optional<BilibiliContentDto> dto = switch (parsed.idType()) {
            case "season" -> fetchPgc(String.format(PGC_BY_SEASON, parsed.contentId()),
                parsed.normalizedUrl());
            case "episode" -> fetchPgc(String.format(PGC_BY_EPISODE, parsed.contentId()),
                parsed.normalizedUrl());
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
        return dto.map(contentMapper::toContent)
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
            return fetchMedia(
                String.format(PGC_BY_MEDIA, contentId.trim()), "")
                .map(contentMapper::toContent);
        }
        if (contentType == ContentType.UPLOADER) {
            return fetchUploader(String.format(UPLOADER_BY_MID, contentId), "").map(contentMapper::toContent);
        }
        return Optional.empty();
    }

    @Override
    public Optional<BilibiliContent> findBySeasonId(
        ContentType contentType, String seasonId
    ) throws Exception {
        if (contentType == null
            || !contentType.isEpisodeTrackable()
            || seasonId == null
            || seasonId.isBlank()) {
            return Optional.empty();
        }
        return fetchPgc(
            String.format(PGC_BY_SEASON, seasonId.trim()), "")
            .map(contentMapper::toContent);
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
            URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        List<BilibiliContent> contents = new ArrayList<>();
        String body = httpClient.getAnonymousSearchText(url);
        if (body == null || body.isBlank()) return List.of();
        ensureSupportedResponse(body);
        for (BilibiliContentDto dto : pageParser.parseSearchMediaJson(body, "")) {
            BilibiliContent content = contentMapper.toContent(dto);
            if (content.getContentType() == contentType) contents.add(content);
            if (contents.size() >= limit) break;
        }
        return contents;
    }

    @Override
    public List<BilibiliContent> searchByTitle(String title, int limit)
        throws Exception {
        if (title == null || title.isBlank() || limit < 1) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 20);
        Map<String, BilibiliContent> unique = new LinkedHashMap<>();
        collectTitleSearchResults(
            unique, "media_bangumi", title.trim(), safeLimit);
        collectTitleSearchResults(
            unique, "media_ft", title.trim(), safeLimit);
        return unique.values().stream().limit(safeLimit).toList();
    }

    private void collectTitleSearchResults(
        Map<String, BilibiliContent> target,
        String searchType,
        String title,
        int limit
    ) throws Exception {
        String url = String.format(
            SEARCH,
            searchType,
            URLEncoder.encode(title, StandardCharsets.UTF_8));
        String body = httpClient.getAnonymousSearchText(url);
        if (body == null || body.isBlank()) return;
        ensureSupportedResponse(body);
        for (BilibiliContentDto dto
            : pageParser.parseSearchMediaJson(body, "")) {
            BilibiliContent content = contentMapper.toContent(dto);
            String key =
                content.getContentType() + ":" + content.getContentId();
            target.putIfAbsent(key, content);
        }
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
            BilibiliContent content = contentMapper.toContent(dto);
            if (content.getContentType() == contentType) contents.add(content);
            if (contents.size() >= limit) break;
        }
        advancePgcIndexPage(contentType, contents.isEmpty());
        return contents;
    }

    private synchronized int nextPgcIndexPage(ContentType contentType) {
        if (crawlStates != null) {
            try {
                return crawlStates.findById(contentType.name())
                    .map(BilibiliCrawlState::getNextPage)
                    .orElse(1);
            } catch (Exception error) {
                System.err.println("[BILIBILI] 读取候选池翻页状态失败，使用内存游标："
                    + error.getMessage());
            }
        }
        return nextPgcIndexPage.getOrDefault(contentType, 1);
    }

    private synchronized void advancePgcIndexPage(
        ContentType contentType,
        boolean reset
    ) {
        int current = nextPgcIndexPage.getOrDefault(contentType, 1);
        if (crawlStates != null) {
            try {
                current = crawlStates.findById(contentType.name())
                    .map(BilibiliCrawlState::getNextPage)
                    .orElse(current);
                crawlStates.save(new BilibiliCrawlState(
                    contentType, reset ? 1 : current + 1));
                return;
            } catch (Exception error) {
                System.err.println("[BILIBILI] 保存候选池翻页状态失败，使用内存游标："
                    + error.getMessage());
            }
        }
        nextPgcIndexPage.put(contentType, reset ? 1 : current + 1);
    }

    private Integer pgcSeasonType(ContentType contentType) {
        if (contentType == ContentType.BANGUMI) return 1;
        if (contentType == ContentType.MOVIE) return 2;
        if (contentType == ContentType.SERIES) return 5;
        return null;
    }

    @Override
    public List<BilibiliContent> findTodayAiring(ContentType contentType)
        throws Exception {
        if (contentType == null) return List.of();
        Integer seasonType = pgcSeasonType(contentType);
        if (seasonType == null) return List.of();

        List<BilibiliContent> contents = new ArrayList<>();
        // 翻 PGC 索引 st=1 前 5 页（150条），找 index_show 含"更新至"的真·连载番
        for (int page = 1; page <= 10 && contents.size() < 30; page++) {
            String url = String.format(
                "https://api.bilibili.com/pgc/season/index/result?"
                + "season_type=%d&type=1&st=1&sort=0&page=%d&pagesize=30",
                seasonType, page);
            try {
                String body = httpClient.getText(url);
                if (body == null || body.isBlank()) break;
                JsonNode root = objectMapper.readTree(body);
                JsonNode list = root.at("/data/list");
                if (!list.isArray() || list.size() == 0) break;
                for (JsonNode item : list) {
                    // 过滤：只取番剧/国创（排除电影、电视剧等）
                    int st = item.has("season_type") && item.get("season_type").isNumber()
                        ? item.get("season_type").asInt() : -1;
                    if (contentType == ContentType.BANGUMI && st != 1) continue;
                    if (contentType == ContentType.SERIES && st != 5) continue;

                    String idx = item.has("index_show")
                        ? item.get("index_show").asText("").trim() : "";
                    if (idx.isEmpty()) continue;
                    // 判断连载中：index_show 含"更新至" 且解析出集数
                    Integer epNum = parseIntFromText(idx);
                    if (!idx.contains("更新至") || epNum == null || epNum <= 0) continue;
                    String seasonId = item.has("season_id")
                        ? item.get("season_id").asText("").trim() : "";
                    String title = item.has("title")
                        ? item.get("title").asText("").trim() : "";
                    if (seasonId.isEmpty() || title.isEmpty()) continue;
                    title = title.replaceAll("(?is)<[^>]+>", "").trim();
                    BilibiliContent c = new BilibiliContent(contentType, seasonId, title);
                    c.setSeasonId(seasonId);
                    if (item.has("cover")) c.setCoverUrl(item.get("cover").asText(""));
                    if (item.has("link")) c.setPageUrl(item.get("link").asText(""));
                    if (item.has("score") && item.get("score").isNumber())
                        c.setRating(item.get("score").asDouble());
                    c.setFinished(false);
                    c.setLatestEpisodeTitle(idx);
                    c.setLatestEpisodeNumber(parseIntFromText(idx));
                    c.setLastFetchedAt(Instant.now());

                    // 前几条调详情 API 拿发布时间
                    if (contents.size() < 5) {
                        try {
                            Optional<BilibiliContent> detail = findBySeasonId(contentType, seasonId);
                            if (detail.isPresent() && detail.get().getLatestEpisodePubTime() != null) {
                                c.setLatestEpisodePubTime(detail.get().getLatestEpisodePubTime());
                            } else if (detail.isPresent() && detail.get().getLatestEpisodeTitle() != null) {
                                c.setLatestEpisodeTitle(detail.get().getLatestEpisodeTitle());
                                if (detail.get().getLatestEpisodeNumber() != null)
                                    c.setLatestEpisodeNumber(detail.get().getLatestEpisodeNumber());
                            }
                        } catch (Exception ignored) { }
                    }

                    contents.add(c);
                }
            } catch (Exception e) {
                System.err.println("[BILIBILI] st=1 page " + page + " 失败: " + e.getMessage());
                break;
            }
        }
        System.out.println("[BILIBILI] st=1 翻页 scan，找到 " + contents.size() + " 条含更新至");
        for (BilibiliContent c : contents.stream().limit(5).toList()) {
            System.out.println("[BILIBILI]   " + c.getTitle() + " → " + c.getLatestEpisodeTitle());
        }
        return contents;
    }

    @Override
    public List<BilibiliContent> findUpdates(
        ContentType contentType, Instant fromInclusive, Instant toExclusive
    ) {
        if (contentType == null || fromInclusive == null || toExclusive == null
            || !fromInclusive.isBefore(toExclusive)) {
            return List.of();
        }

        // 时间表请求量较低，优先使用；缺失时再对少量连载作品调用详情接口核验发布时间。
        Map<String, BilibiliContent> verified = new LinkedHashMap<>();
        Integer seasonType = pgcSeasonType(contentType);
        List<BilibiliContent> timeline = seasonType == null
            ? List.of()
            : tryFetchTimeline(String.format(PGC_TIMELINE, seasonType), contentType);
        collectVerifiedUpdates(verified, timeline, fromInclusive, toExclusive);
        List<BilibiliContent> fallback = List.of();
        if (verified.size() < 8) {
            fallback = fallbackPgcIndexToday(contentType);
            collectVerifiedUpdates(verified, fallback, fromInclusive, toExclusive);
        }
        boolean receivedCandidates = !timeline.isEmpty() || !fallback.isEmpty();
        boolean receivedAnyTimestamp = java.util.stream.Stream.concat(
                timeline.stream(), fallback.stream())
            .anyMatch(content -> content.getLatestEpisodePubTime() != null);
        if (receivedCandidates && !receivedAnyTimestamp) {
            throw new IllegalStateException("B站返回了作品，但没有提供可用的更新时间");
        }
        return verified.values().stream()
            .sorted((left, right) -> right.getLatestEpisodePubTime()
                .compareTo(left.getLatestEpisodePubTime()))
            .toList();
    }

    private static void collectVerifiedUpdates(
        Map<String, BilibiliContent> target,
        List<BilibiliContent> candidates,
        Instant fromInclusive,
        Instant toExclusive
    ) {
        if (candidates == null) return;
        for (BilibiliContent content : candidates) {
            Instant publishedAt = content.getLatestEpisodePubTime();
            if (publishedAt == null || publishedAt.isBefore(fromInclusive)
                || !publishedAt.isBefore(toExclusive)) {
                continue;
            }
            String key = content.getContentId();
            if (key == null || key.isBlank()) key = content.getSeasonId();
            if (key == null || key.isBlank()) key = content.getTitle();
            if (key != null && !key.isBlank()) target.putIfAbsent(key, content);
        }
    }

    /** 爬 B站动画时间表网页，提取 __INITIAL_STATE__ 中的当日更新数据 */
    private List<BilibiliContent> tryFetchTimelineWebPage(ContentType contentType) {
        try {
            String html = httpClient.getText("https://www.bilibili.com/anime/timeline");
            if (html == null || html.isBlank()) return List.of();
            // 提取 __INITIAL_STATE__ JSON
            String json = extractInitialState(html);
            if (json == null) {
                System.err.println("[BILIBILI] 网页未找到 __INITIAL_STATE__");
                return List.of();
            }
            JsonNode root = objectMapper.readTree(json);
            // 数据在 root.timeline，结构类似 { daily: [...] } 或直接是数组
            JsonNode timelineNode = root.path("timeline");
            if (timelineNode.isMissingNode()) {
                System.err.println("[BILIBILI] 网页未找到 timeline 节点");
                return List.of();
            }
            // timeline 可能是 { daily: [{seasons:[...]}] } 或 [{seasons:[...]}]
            JsonNode dailyData = timelineNode.path("daily");
            if (dailyData.isMissingNode() || !dailyData.isArray()) {
                // 也可能是 timeline 本身就是数组
                if (timelineNode.isArray()) dailyData = timelineNode;
            }
            if (!dailyData.isArray() || dailyData.size() == 0) {
                // 打印 timelineNode 的 key 进一步调试
                java.util.Iterator<String> tlKeys = timelineNode.fieldNames();
                java.util.List<String> tlKeyList = new java.util.ArrayList<>();
                while (tlKeys.hasNext()) tlKeyList.add(tlKeys.next());
                System.err.println("[BILIBILI] timeline keys: " + String.join(", ", tlKeyList)
                    + " isArray=" + timelineNode.isArray());
                return List.of();
            }
            List<BilibiliContent> contents = new ArrayList<>();
            for (JsonNode day : dailyData) {
                JsonNode seasons = day.path("seasons");
                if (!seasons.isArray() && day.has("season_id")) {
                    seasons = objectMapper.createArrayNode().add(day);
                }
                if (!seasons.isArray()) continue;
                for (JsonNode item : seasons) {
                    String seasonId = pathText(item, "season_id");
                    String title = pathText(item, "title");
                    if (seasonId.isBlank() || title.isBlank()) continue;
                    BilibiliContent content = new BilibiliContent(contentType, seasonId,
                        title.replaceAll("(?is)<[^>]+>", "").trim());
                    content.setCoverUrl(pathText(item, "cover"));
                    content.setPageUrl(pathText(item, "url"));
                    String pubIndex = pathText(item, "pub_index");
                    content.setLatestEpisodeTitle(pubIndex);
                    content.setLatestEpisodeNumber(parseIntFromText(pubIndex));
                    content.setLatestEpisodePubTime(parseTimelinePubTime(item));
                    content.setLastFetchedAt(Instant.now());
                    contents.add(content);
                }
            }
            System.out.println("[BILIBILI] 网页时间表返回 " + contents.size() + " 条");
            return contents;
        } catch (Exception e) {
            System.err.println("[BILIBILI] 网页时间表解析失败: " + e.getMessage());
            return List.of();
        }
    }

    private List<BilibiliContent> tryFetchTimeline(String url, ContentType contentType) {
        try {
            String body = httpClient.getText(url);
            if (body == null || body.isBlank()) return List.of();
            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root.path("result");
            if (!result.isArray()) result = root.path("data");
            if (!result.isArray() || result.isEmpty()) return List.of();
            List<BilibiliContent> contents = new ArrayList<>();
            for (JsonNode day : result) {
                JsonNode episodes = day.path("episodes");
                if (!episodes.isArray()) continue;
                for (JsonNode ep : episodes) {
                    String seasonId = pathText(ep, "season_id");
                    String title = pathText(ep, "title");
                    if (seasonId.isBlank() || title.isBlank()) continue;
                    BilibiliContent content = new BilibiliContent(contentType, seasonId, title);
                    content.setSeasonId(seasonId);
                    content.setCoverUrl(pathText(ep, "cover"));
                    content.setPageUrl("https://www.bilibili.com/bangumi/play/ss" + seasonId);
                    String pubIndex = pathText(ep, "pub_index");
                    content.setLatestEpisodeTitle(pubIndex);
                    content.setLatestEpisodeNumber(parseIntFromText(pubIndex));
                    content.setLatestEpisodePubTime(parseTimelinePubTime(ep));
                    content.setLastFetchedAt(Instant.now());
                    contents.add(content);
                }
            }
            contents.sort((a, b) -> {
                Instant ta = a.getLatestEpisodePubTime();
                Instant tb = b.getLatestEpisodePubTime();
                if (ta == null && tb == null) return 0;
                if (ta == null) return 1;
                if (tb == null) return -1;
                return tb.compareTo(ta);
            });
            System.out.println("[BILIBILI] 时间线 API 命中: " + url);
            return contents;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 兜底：PGC 索引 st=2 取连载列表，再逐个调详情 API 拿准确集数+发布时间 */
    private List<BilibiliContent> fallbackPgcIndexToday(ContentType contentType) {
        try {
            Integer seasonType = pgcSeasonType(contentType);
            if (seasonType == null) return List.of();
            String url = String.format(
                "https://api.bilibili.com/pgc/season/index/result?"
                + "season_type=%d&type=1&st=2&sort=0&page=1&pagesize=20", seasonType);
            String body = httpClient.getText(url);
            if (body == null || body.isBlank()) return List.of();

            // 先用解析器拿基础字段
            List<BilibiliContentDto> dtos = pageParser.parsePgcIndexJson(body, contentType);
            if (dtos.isEmpty()) return List.of();

            // 逐个调详情 API，最多 5 个，拿到就停（限速 350ms 每个）
            List<BilibiliContent> contents = new ArrayList<>();
            for (BilibiliContentDto dto : dtos) {
                if (dto.getSeasonId() == null || dto.getSeasonId().isBlank()) continue;
                try {
                    BilibiliContent detail = findBySeasonId(contentType, dto.getSeasonId())
                        .orElse(null);
                    if (detail != null) {
                        contents.add(detail);
                    if (contents.size() >= 8) break; // 最多 8 个，避免太慢
                    }
                } catch (Exception ignored) {
                    // 单个失败不影响整体
                }
            }
            long withTime = contents.stream()
                .filter(c -> c.getLatestEpisodePubTime() != null).count();
            System.out.println("[BILIBILI] 兜底详情 API 返回 " + contents.size()
                + " 条，含 pubTime 的 " + withTime);
            return contents;
        } catch (Exception e) {
            System.err.println("[BILIBILI] 兜底详情 API 失败: " + e.getMessage());
            return List.of();
        }
    }

    private static String extractInitialState(String html) {
        for (String prefix : new String[]{"__INITIAL_STATE__", "window.__INITIAL_STATE__"}) {
            int idx = html.indexOf(prefix + " = ");
            if (idx < 0) idx = html.indexOf(prefix + "=");
            if (idx < 0) continue;
            idx = html.indexOf('{', idx);
            if (idx < 0) continue;
            int depth = 0, end = idx;
            for (int i = idx; i < html.length(); i++) {
                char c = html.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
            }
            if (end > idx) return html.substring(idx, end);
        }
        return null;
    }

    private static String pathText(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? "" : child.asText("").trim();
    }

    private static Integer parseIntFromText(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String digits = text.replaceAll("\\D+", "");
            return digits.isBlank() ? null : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Instant parseTimelinePubTime(JsonNode ep) {
        // pub_ts: Unix 秒级时间戳
        JsonNode pubTs = ep.path("pub_ts");
        if (pubTs.isLong() || pubTs.isInt()) {
            long ts = pubTs.asLong();
            if (ts > 0) return Instant.ofEpochSecond(ts);
        }
        // pub_time: "10:00" 或 "2024-07-31 10:00:00"
        JsonNode pubTime = ep.path("pub_time");
        if (pubTime.isTextual()) {
            String text = pubTime.asText().trim();
            if (text.isBlank()) return null;
            try {
                // 先尝试完整日期时间
                return Instant.from(
                    java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .parse(text));
            } catch (Exception ignored) {}
            try {
                // 再尝试仅时间（HH:mm），结合今天日期
                java.time.LocalTime lt = java.time.LocalTime.parse(text);
                return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                    .atTime(lt)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toInstant();
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public BilibiliContent refresh(BilibiliContent content) throws Exception {
        if (content == null) throw new IllegalArgumentException("content 不能为空");
        if (content.getSeasonId() != null
            && !content.getSeasonId().isBlank()) {
            return findBySeasonId(
                content.getContentType(), content.getSeasonId())
                .orElse(content);
        }
        return findByContentId(
            content.getContentType(), content.getContentId())
            .orElse(content);
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
                || body.contains("\"code\":-403")
                || body.contains("\"errcode\":-14")
                || body.toLowerCase().contains("session timeout")
                || body.matches("(?is).*<title>\s*出错啦!\s*-\s*aba\\.bilibili\\.com\s*</title>.*")) {
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

}
