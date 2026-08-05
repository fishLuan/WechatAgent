package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class BilibiliRagRetriever implements BilibiliRagRetrievalService {
    private static final int MAX_CANDIDATES = 120;

    private final BilibiliContentRepository contents;

    public BilibiliRagRetriever(BilibiliContentRepository contents) {
        this.contents = contents;
    }

    @Override
    public List<BilibiliRagDocument> retrieve(
        String question, ContentType preferredType, String referenceTitle, int limit
    ) {
        Set<String> terms = tokenize(question + " " + nullToEmpty(referenceTitle));
        List<BilibiliContent> candidates = loadCandidates(preferredType);
        return candidates.stream()
            .filter(content -> matchesBusinessType(content, preferredType))
            .map(content -> new ScoredContent(content, score(content, terms, referenceTitle)))
            .filter(item -> item.score().relevance() > 0 || terms.isEmpty())
            .sorted(Comparator
                .comparingDouble((ScoredContent item) -> item.score().total()).reversed()
                .thenComparing(item -> item.content().getRating(), Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(Math.max(1, limit))
            .map(item -> BilibiliRagDocument.from(item.content()))
            .toList();
    }

    private List<BilibiliContent> loadCandidates(ContentType preferredType) {
        if (preferredType != null) {
            return contents.findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(preferredType, 0);
        }
        return contents.findAll().stream()
            .sorted(Comparator.comparing(
                BilibiliContent::getRating,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(MAX_CANDIDATES)
            .toList();
    }

    private boolean matchesBusinessType(BilibiliContent content, ContentType preferredType) {
        if (preferredType == null) return true;
        if (content.getContentType() != preferredType) return false;
        if ((preferredType == ContentType.BANGUMI || preferredType == ContentType.SERIES)
            && looksLikeMovie(content)) {
            return false;
        }
        return true;
    }

    private boolean looksLikeMovie(BilibiliContent content) {
        String text = searchableText(content) + " " + normalized(content.getPageUrl());
        if (text.contains("theme=movie")
            || text.contains("剧场版")
            || text.contains("映画")
            || text.contains("电影")
            || text.contains("movie")) {
            return true;
        }
        return content.isFinished()
            && content.getLatestEpisodeNumber() != null
            && content.getLatestEpisodeNumber() <= 1;
    }

    private Score score(BilibiliContent content, Set<String> terms, String referenceTitle) {
        String haystack = searchableText(content);
        double relevance = 0;
        for (String term : terms) {
            if (haystack.contains(term)) relevance += term.length() >= 2 ? 2 : 1;
        }
        if (hasText(referenceTitle)
            && normalized(content.getTitle()).contains(normalized(referenceTitle))) {
            relevance += 8;
        }
        double quality = content.getRating() == null ? 0 : content.getRating() / 10.0;
        if (content.getViewCount() != null && content.getViewCount() > 0) quality += 0.2;
        return new Score(relevance + quality, relevance);
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = stripIntentWords(normalized(text));
        for (String part : normalized.split("[\\s,，。！？《》【】()（）:：/\\\\\\-—_]+")) {
            if (part.length() >= 2) terms.add(part);
        }
        for (int i = 0; i + 2 <= normalized.length(); i++) {
            char left = normalized.charAt(i);
            char right = normalized.charAt(i + 1);
            if (isHan(left) && isHan(right)) terms.add(normalized.substring(i, i + 2));
        }
        return terms;
    }

    private String stripIntentWords(String value) {
        String result = value;
        for (String word : List.of(
            "智能推荐", "为什么推荐", "为啥推荐", "推荐", "类似", "相似",
            "同类型", "同题材", "适合我", "按我的偏好", "有没有", "好看的",
            "来点", "找点", "最近看什么", "动漫", "番剧", "电视剧", "电影", "剧集")) {
            result = result.replace(word, " ");
        }
        return result;
    }

    private boolean isHan(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private String searchableText(BilibiliContent content) {
        return normalized(String.join(" ",
            nullToEmpty(content.getTitle()),
            nullToEmpty(content.getDescription()),
            String.join(" ", content.getGenres()),
            nullToEmpty(content.getLatestEpisodeTitle())));
    }

    private String normalized(String value) {
        return nullToEmpty(value).toLowerCase(Locale.ROOT).trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ScoredContent(BilibiliContent content, Score score) {
    }

    private record Score(double total, double relevance) {
    }
}
