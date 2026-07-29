package com.clawbot.wechatbot.feature.bilibili.source.parser;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 B 站作品、番剧、电影和 UP 主链接。 */
public class BilibiliUrlParser {
    private static final Pattern BV = Pattern.compile("(?i)(BV[0-9A-Za-z]{10})");
    private static final Pattern AV = Pattern.compile("(?i)(?:^|/)av(\\d+)");
    private static final Pattern SEASON = Pattern.compile("(?i)(?:ss|season_id=)(\\d+)");
    private static final Pattern EPISODE = Pattern.compile("(?i)(?:ep|ep_id=)(\\d+)");
    private static final Pattern MEDIA = Pattern.compile("(?i)(?:md|media_id=)(\\d+)");
    private static final Pattern LIVE = Pattern.compile("^/(\\d+)");
    private static final Pattern DYNAMIC = Pattern.compile("(?i)(?:/(?:opus|dynamic))?/(\\d+)");
    private static final Pattern UPLOADER = Pattern.compile("(?i)/(?:space|medialist)/(?:(?:[^/]+/)?)(\\d+)");

    public Optional<ParsedBilibiliUrl> parse(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String normalized = normalize(url);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.endsWith("bilibili.com") && !host.equals("b23.tv")) {
            return Optional.empty();
        }

        String text = normalized;
        Optional<String> seasonId = first(SEASON, text);
        if (seasonId.isPresent()) {
            ContentType type = text.toLowerCase(Locale.ROOT).contains("movie")
                ? ContentType.MOVIE
                : ContentType.BANGUMI;
            return Optional.of(new ParsedBilibiliUrl(type, "season", seasonId.get(), normalized));
        }
        Optional<String> episodeId = first(EPISODE, text);
        if (episodeId.isPresent()) {
            return Optional.of(new ParsedBilibiliUrl(
                ContentType.BANGUMI, "episode", episodeId.get(), normalized));
        }
        Optional<String> mediaId = first(MEDIA, text);
        if (mediaId.isPresent()) {
            return Optional.of(new ParsedBilibiliUrl(
                ContentType.BANGUMI, "media", mediaId.get(), normalized));
        }
        Optional<String> bv = first(BV, text);
        if (bv.isPresent()) {
            return Optional.of(new ParsedBilibiliUrl(
                ContentType.SERIES, "bvid", bv.get(), normalized));
        }
        Optional<String> av = first(AV, uri.getPath());
        if (av.isPresent()) {
            return Optional.of(new ParsedBilibiliUrl(
                ContentType.SERIES, "avid", av.get(), normalized));
        }
        Optional<String> mid = host.startsWith("space.bilibili.com")
            ? first(Pattern.compile("^/(\\d+)"), uri.getPath())
            : first(UPLOADER, uri.getPath());
        if (mid.isPresent()) {
            return Optional.of(new ParsedBilibiliUrl(
                ContentType.UPLOADER, "mid", mid.get(), normalized));
        }
        if (host.startsWith("live.bilibili.com")) {
            Optional<String> roomId = first(LIVE, uri.getPath());
            if (roomId.isPresent()) {
                return Optional.of(new ParsedBilibiliUrl(
                    ContentType.SERIES, "live", roomId.get(), normalized));
            }
        }
        if (host.equals("t.bilibili.com") || host.equals("www.bilibili.com")) {
            Optional<String> dynamicId = first(DYNAMIC, uri.getPath());
            if (dynamicId.isPresent()) {
                return Optional.of(new ParsedBilibiliUrl(
                    ContentType.SERIES, "dynamic", dynamicId.get(), normalized));
            }
        }
        return Optional.empty();
    }

    private String normalize(String url) {
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "http://", 0, 7)
                && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    private Optional<String> first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public record ParsedBilibiliUrl(
        ContentType contentType,
        String idType,
        String contentId,
        String normalizedUrl
    ) {
    }
}
