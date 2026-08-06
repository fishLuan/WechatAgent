package com.clawbot.wechatbot.memory;

import com.clawbot.wechatbot.intent.IntentResult;
import com.clawbot.wechatbot.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds a minimal model context according to request type and relevance. */
@Component
public final class ConversationContextSelector {
    private static final List<Domain> DOMAINS = List.of(
        new Domain("weather", Set.of("天气", "气温", "温度", "下雨", "降雨", "穿衣")),
        new Domain("route", Set.of("路线", "导航", "怎么走", "出行", "驾车", "公交")),
        new Domain("news", Set.of("新闻", "头条", "热搜", "资讯")),
        new Domain("bilibili", Set.of("b站", "动漫", "番剧", "电影", "电视剧", "订阅", "追更")),
        new Domain("book", Set.of("书", "图书", "小说", "作者", "微信读书")),
        new Domain("excel", Set.of("excel", "表格", "行", "列", "工作表")),
        new Domain("document", Set.of("文档", "pdf", "word", "文件")),
        new Domain("voice", Set.of("语音", "男声", "女声", "朗读")),
        new Domain("schedule", Set.of("定时", "提醒", "推送", "每天", "每周")),
        new Domain("currency", Set.of("汇率", "人民币", "美元", "日元", "欧元", "港币"))
    );
    private static final Set<String> FOLLOW_UP_MARKERS = Set.of(
        "继续", "接着", "第三个", "第二个", "第一个", "上一个", "下一个",
        "男声回复", "女声回复", "再读一遍", "换成", "链接呢", "订阅它",
        "这个", "那个", "它", "刚才", "上面");

    private final MemoryProperties properties;

    public ConversationContextSelector(MemoryProperties properties) {
        this.properties = properties;
    }

    public Selection select(
        ConversationMemory memory,
        String userText,
        IntentResult intent,
        Set<String> allowedTools,
        boolean complexRequest
    ) {
        ConversationMemory safe = memory == null ? new ConversationMemory() : memory;
        String text = normalize(userText);
        boolean followUp = isFollowUp(text);
        if (!followUp && allowedTools != null && !allowedTools.isEmpty()
            && !complexRequest) {
            return new Selection("", Mode.INDEPENDENT_TOOL, 0);
        }
        List<Turn> turns = turns(safe.getRecentMessages());
        if (turns.isEmpty() && safe.getLongTermSummary().isBlank()) {
            return new Selection("", mode(followUp, complexRequest), 0);
        }
        if (followUp) {
            List<Turn> selected = selectFollowUp(turns, text,
                properties.getFollowUpContextTurns());
            return selection(selected, "", Mode.FOLLOW_UP);
        }
        if (!complexRequest) {
            List<Turn> selected = tail(turns, properties.getGeneralContextTurns());
            return selection(selected, "", Mode.GENERAL_CHAT);
        }
        List<Turn> selected = selectRelevant(
            turns, text, properties.getComplexContextTurns());
        String summary = relevantSummary(safe.getLongTermSummary(), text)
            ? safe.getLongTermSummary() : "";
        return selection(selected, summary, Mode.SEMANTIC_COMPLEX);
    }

    public boolean isFollowUp(String text) {
        String normalized = normalize(text);
        if (FOLLOW_UP_MARKERS.stream().anyMatch(normalized::contains)) return true;
        return normalized.matches(
            "^(?:订阅|看过|选择)?第?[一二三四五六七八九十\\d]+个?$"
                + "|^(?:是|不是|对|不对|嗯|好的)[？?]?$");
    }

    private List<Turn> selectFollowUp(List<Turn> turns, String query, int limit) {
        Set<String> domains = domains(query);
        if (domains.isEmpty()) {
            domains = latestDomains(turns);
        }
        if (domains.isEmpty()) return tail(turns, limit);
        Set<String> targetDomains = domains;
        List<Turn> matches = turns.stream()
            .filter(turn -> !disjoint(targetDomains, domains(turn.text())))
            .toList();
        return matches.isEmpty() ? tail(turns, limit) : tail(matches, limit);
    }

    private Set<String> latestDomains(List<Turn> turns) {
        for (int index = turns.size() - 1; index >= 0; index--) {
            Set<String> found = domains(turns.get(index).text());
            if (!found.isEmpty()) return found;
        }
        return Set.of();
    }

    private List<Turn> selectRelevant(List<Turn> turns, String query, int limit) {
        Set<String> queryDomains = domains(query);
        Set<String> queryBigrams = bigrams(query);
        List<ScoredTurn> scored = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            Turn turn = turns.get(index);
            Set<String> turnDomains = domains(turn.text());
            Set<String> turnBigrams = bigrams(turn.text());
            double score = overlap(queryBigrams, turnBigrams) * 3D;
            if (!disjoint(queryDomains, turnDomains)) score += 8D;
            score += (index + 1D) / Math.max(1D, turns.size());
            if (score > 1D) scored.add(new ScoredTurn(turn, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredTurn::score).reversed());
        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        scored.stream().limit(limit).forEach(item ->
            selectedIndexes.add(item.turn().index()));
        if (selectedIndexes.isEmpty()) {
            return tail(turns, Math.min(2, limit));
        }
        return turns.stream().filter(turn -> selectedIndexes.contains(turn.index())).toList();
    }

    private Selection selection(List<Turn> turns, String summary, Mode mode) {
        StringBuilder context = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            append(context, "system", "【相关长期记忆】\n" + summary.trim());
        }
        for (Turn turn : turns) {
            turn.messages().forEach(message ->
                append(context, message.role(), message.content()));
        }
        int count = turns.stream().mapToInt(turn -> turn.messages().size()).sum();
        return new Selection(context.toString(), mode, count);
    }

    private void append(StringBuilder context, String role, String content) {
        if (context.length() > 0) context.append(',');
        context.append("{\"role\":").append(JsonUtils.escape(role))
            .append(",\"content\":").append(JsonUtils.escape(content)).append('}');
    }

    private List<Turn> turns(List<ConversationMessage> messages) {
        List<Turn> turns = new ArrayList<>();
        List<ConversationMessage> current = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if ("user".equals(message.role()) && !current.isEmpty()) {
                turns.add(new Turn(turns.size(), List.copyOf(current)));
                current.clear();
            }
            current.add(message);
        }
        if (!current.isEmpty()) turns.add(new Turn(turns.size(), List.copyOf(current)));
        return turns;
    }

    private List<Turn> tail(List<Turn> turns, int limit) {
        int start = Math.max(0, turns.size() - Math.max(1, limit));
        return List.copyOf(turns.subList(start, turns.size()));
    }

    private boolean relevantSummary(String summary, String query) {
        if (summary == null || summary.isBlank()) return false;
        return !disjoint(domains(summary), domains(query))
            || overlap(bigrams(summary), bigrams(query)) >= 2;
    }

    private Set<String> domains(String text) {
        String normalized = normalize(text);
        Set<String> found = new LinkedHashSet<>();
        for (Domain domain : DOMAINS) {
            if (domain.words().stream().anyMatch(normalized::contains)) {
                found.add(domain.name());
            }
        }
        return found;
    }

    private Set<String> bigrams(String text) {
        String normalized = normalize(text).replaceAll("[\\p{P}\\p{S}\\s]+", "");
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < normalized.length(); index++) {
            result.add(normalized.substring(index, index + 2));
        }
        return result;
    }

    private int overlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String item : left) if (right.contains(item)) count++;
        return count;
    }

    private boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Mode mode(boolean followUp, boolean complex) {
        return followUp ? Mode.FOLLOW_UP
            : complex ? Mode.SEMANTIC_COMPLEX : Mode.GENERAL_CHAT;
    }

    public enum Mode { INDEPENDENT_TOOL, FOLLOW_UP, GENERAL_CHAT, SEMANTIC_COMPLEX }
    public record Selection(String context, Mode mode, int selectedMessages) { }
    private record Domain(String name, Set<String> words) { }
    private record Turn(int index, List<ConversationMessage> messages) {
        private String text() {
            return messages.stream().map(ConversationMessage::content)
                .reduce((left, right) -> left + " " + right).orElse("");
        }
    }
    private record ScoredTurn(Turn turn, double score) { }
}
