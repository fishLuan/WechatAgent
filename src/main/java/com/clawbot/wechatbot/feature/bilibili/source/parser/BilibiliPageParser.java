package com.clawbot.wechatbot.feature.bilibili.source.parser;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliContentDto;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliEpisodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 B 站公开接口或页面内 JSON 转换为采集模块 DTO。 */
public class BilibiliPageParser {
    private static final Pattern INITIAL_STATE =
        Pattern.compile("(?s)__INITIAL_STATE__\\s*=\\s*(\\{.*?})\\s*;\\s*\\(function");

    private final ObjectMapper objectMapper;

    public BilibiliPageParser() {
        this(new ObjectMapper());
    }

    public BilibiliPageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<BilibiliContentDto> parsePgcJson(String json, String pageUrl) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = firstPresent(root, "/result", "/data/result", "/data");
            if (result.isMissingNode()) return Optional.empty();
            return Optional.ofNullable(parsePgcResult(result, pageUrl));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<BilibiliContentDto> parseVideoJson(String json, String pageUrl) {
        try {
            JsonNode data = firstPresent(objectMapper.readTree(json), "/data", "/result");
            if (data.isMissingNode()) return Optional.empty();
            BilibiliContentDto dto = new BilibiliContentDto();
            dto.setContentType(ContentType.SERIES);
            dto.setContentId(text(data, "bvid", text(data, "aid", "")));
            dto.setTitle(text(data, "title", ""));
            dto.setDescription(text(data, "desc", ""));
            dto.setCoverUrl(text(data, "pic", ""));
            dto.setPageUrl(pageUrl);
            dto.setViewCount(longValue(data.path("stat").path("view")));
            dto.setLatestEpisode(new BilibiliEpisodeDto(
                text(data, "cid", ""),
                text(data, "title", ""),
                intValue(data.path("videos")),
                pageUrl));
            return hasRequired(dto) ? Optional.of(dto) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<BilibiliContentDto> parseSearchMediaJson(String json, String pageUrl) {
        List<BilibiliContentDto> contents = new ArrayList<>();
        try {
            JsonNode result = firstPresent(objectMapper.readTree(json), "/data/result");
            if (!result.isArray()) return contents;
            for (JsonNode item : result) {
                BilibiliContentDto dto = new BilibiliContentDto();
                String typeName = text(item, "season_type_name", text(item, "type_name", ""));
                dto.setContentType(resolvePgcType(intValue(item.path("season_type")), typeName));
                dto.setContentId(text(item, "media_id", text(item, "season_id", "")));
                dto.setSeasonId(text(item, "season_id", ""));
                dto.setTitle(cleanHtml(text(item, "title", "")));
                dto.setDescription(cleanHtml(text(item, "desc", "")));
                dto.setCoverUrl(text(item, "cover", ""));
                dto.setPageUrl(text(item, "url", pageUrl));
                dto.setRating(doubleValue(firstPresent(item, "/media_score/score", "/rating/score")));
                dto.setGenres(parseGenres(item));
                if (hasRequired(dto)) contents.add(dto);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return contents;
    }

    public List<BilibiliContentDto> parsePgcIndexJson(
        String json,
        ContentType requestedType
    ) {
        List<BilibiliContentDto> contents = new ArrayList<>();
        try {
            JsonNode list = firstPresent(objectMapper.readTree(json), "/data/list");
            if (!list.isArray()) return contents;
            for (JsonNode item : list) {
                BilibiliContentDto dto = new BilibiliContentDto();
                String typeName = text(item, "type_name", "");
                ContentType resolvedType = resolvePgcType(
                    intValue(item.path("season_type")), typeName);
                dto.setContentType(normalizeCandidateType(resolvedType, requestedType));
                dto.setContentId(text(item, "media_id", text(item, "season_id", "")));
                dto.setSeasonId(text(item, "season_id", ""));
                dto.setTitle(cleanHtml(text(item, "title", "")));
                dto.setDescription(cleanHtml(text(item, "subTitle", "")));
                dto.setCoverUrl(text(item, "cover", ""));
                dto.setPageUrl(text(item, "link", ""));
                dto.setRating(doubleValue(item.path("score")));
                dto.setFinished(Integer.valueOf(1).equals(intValue(item.path("is_finish"))));
                JsonNode firstEp = item.path("first_ep");
                if (!firstEp.isMissingNode() && !firstEp.isNull()) {
                    dto.setLatestEpisode(new BilibiliEpisodeDto(
                        text(firstEp, "ep_id", ""),
                        text(item, "index_show", ""),
                        intValue(item.path("index_show")),
                        dto.getPageUrl()));
                }
                if (hasRequired(dto)) contents.add(dto);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return contents;
    }

    public Optional<BilibiliContentDto> parseMediaJson(String json, String pageUrl) {
        try {
            JsonNode media = firstPresent(objectMapper.readTree(json), "/result/media");
            if (media.isMissingNode()) return Optional.empty();
            BilibiliContentDto dto = new BilibiliContentDto();
            String typeName = text(media, "type_name", "");
            dto.setContentType(resolvePgcType(intValue(media.path("type")), typeName));
            dto.setContentId(text(media, "media_id", ""));
            dto.setSeasonId(text(media, "season_id", ""));
            dto.setTitle(text(media, "title", ""));
            dto.setDescription(text(media, "evaluate", ""));
            dto.setCoverUrl(text(media, "cover", ""));
            dto.setPageUrl(text(media, "share_url", pageUrl));
            dto.setRating(doubleValue(firstPresent(media, "/rating/score")));
            JsonNode newEp = media.path("new_ep");
            if (!newEp.isMissingNode() && !newEp.isNull()) {
                dto.setLatestEpisode(new BilibiliEpisodeDto(
                    text(newEp, "id", ""),
                    text(newEp, "index_show", text(newEp, "index", "")),
                    intValue(newEp.path("index")),
                    pageUrl));
            }
            return hasRequired(dto) ? Optional.of(dto) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<BilibiliContentDto> parseUploaderJson(String json, String pageUrl) {
        try {
            JsonNode card = firstPresent(objectMapper.readTree(json), "/data/card");
            if (card.isMissingNode()) return Optional.empty();
            BilibiliContentDto dto = new BilibiliContentDto();
            dto.setContentType(ContentType.UPLOADER);
            dto.setContentId(text(card, "mid", ""));
            dto.setTitle(text(card, "name", ""));
            dto.setDescription(text(card, "sign", ""));
            dto.setCoverUrl(text(card, "face", ""));
            dto.setPageUrl(pageUrl);
            dto.setViewCount(longValue(card.path("fans")));
            return hasRequired(dto) ? Optional.of(dto) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<BilibiliContentDto> parseInitialState(String html, String pageUrl) {
        Matcher matcher = INITIAL_STATE.matcher(html == null ? "" : html);
        if (!matcher.find()) return Optional.empty();
        return parsePgcJson(matcher.group(1), pageUrl);
    }

    private BilibiliContentDto parsePgcResult(JsonNode result, String pageUrl) {
        BilibiliContentDto dto = new BilibiliContentDto();
        Integer seasonType = intValue(result.path("season_type"));
        String typeName = text(result, "type_name", "");
        dto.setContentType(resolvePgcType(seasonType, typeName));
        dto.setContentId(text(result, "media_id", text(result, "season_id", "")));
        dto.setSeasonId(text(result, "season_id", ""));
        dto.setTitle(text(result, "title", text(result, "season_title", "")));
        dto.setDescription(text(result, "evaluate", text(result, "description", "")));
        dto.setCoverUrl(text(result, "cover", ""));
        dto.setPageUrl(pageUrl);
        dto.setRating(doubleValue(firstPresent(result, "/rating/score", "/new_ep/index_show")));
        dto.setGenres(parseGenres(result));
        JsonNode episodes = result.path("episodes");
        if (episodes.isArray() && episodes.size() > 0) {
            JsonNode latest = episodes.get(episodes.size() - 1);
            dto.setLatestEpisode(new BilibiliEpisodeDto(
                text(latest, "id", text(latest, "ep_id", "")),
                text(latest, "long_title", text(latest, "title", "")),
                intValue(latest.path("title")),
                text(latest, "link", pageUrl)));
        }
        Integer isFinish = intValue(result.path("is_finish"));
        dto.setFinished(Integer.valueOf(1).equals(isFinish)
            || "已完结".equals(text(result, "status", "")));
        return hasRequired(dto) ? dto : null;
    }

    private ContentType resolvePgcType(Integer seasonType, String typeName) {
        String name = typeName == null ? "" : typeName;
        if (Integer.valueOf(2).equals(seasonType) || name.contains("电影")) {
            return ContentType.MOVIE;
        }
        if (Integer.valueOf(1).equals(seasonType)
                || name.contains("番剧")
                || name.contains("国创")) {
            return ContentType.BANGUMI;
        }
        return ContentType.SERIES;
    }

    private ContentType normalizeCandidateType(
        ContentType resolvedType,
        ContentType requestedType
    ) {
        if (requestedType == ContentType.MOVIE) return ContentType.MOVIE;
        if (requestedType == ContentType.BANGUMI) return ContentType.BANGUMI;
        if (requestedType == ContentType.SERIES) return ContentType.SERIES;
        return resolvedType;
    }

    private Set<String> parseGenres(JsonNode result) {
        Set<String> genres = new LinkedHashSet<>();
        JsonNode styles = firstPresent(result, "/styles", "/style");
        if (styles.isArray()) {
            styles.forEach(node -> {
                String value = node.isTextual() ? node.asText() : text(node, "name", "");
                if (!value.isBlank()) genres.add(value);
            });
        } else if (styles.isTextual()) {
            for (String value : styles.asText().split("[,，/ ]+")) {
                if (!value.isBlank()) genres.add(value);
            }
        }
        return genres;
    }

    private boolean hasRequired(BilibiliContentDto dto) {
        return dto.getContentType() != null
            && dto.getContentId() != null && !dto.getContentId().isBlank()
            && dto.getTitle() != null && !dto.getTitle().isBlank();
    }

    private JsonNode firstPresent(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (!node.isMissingNode() && !node.isNull()) return node;
        }
        return MissingNode.getInstance();
    }

    private String cleanHtml(String text) {
        return text == null ? "" : text.replaceAll("(?is)<[^>]+>", "").trim();
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return fallback;
        return value.asText(fallback);
    }

    private Integer intValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isInt() || node.isLong()) return node.asInt();
        try {
            String digits = node.asText("").replaceAll("\\D+", "");
            return digits.isBlank() ? null : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        try {
            return Long.parseLong(node.asText(""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double doubleValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asDouble();
        try {
            return Double.parseDouble(node.asText(""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
