package com.clawbot.wechatbot.tools.bazitool;

import com.clawbot.wechatbot.tools.bazitool.model.BaziChart;
import com.clawbot.wechatbot.tools.bazitool.model.FortuneAnalysis;
import com.clawbot.wechatbot.tools.bazitool.model.PillarDetails;
import com.nlf.calendar.Solar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transparent rule-based relations for a selected traditional fortune year. */
final class BaziFortuneAnalyzer {
    private static final Set<String> BRANCH_COMBINES = Set.of(
        "子丑", "寅亥", "卯戌", "辰酉", "巳申", "午未");
    private static final Set<String> BRANCH_CLASHES = Set.of(
        "子午", "丑未", "寅申", "卯酉", "辰戌", "巳亥");
    private static final Set<String> BRANCH_HARMS = Set.of(
        "子未", "丑午", "寅巳", "卯辰", "申亥", "酉戌");
    private static final Set<String> STEM_COMBINES = Set.of(
        "甲己", "乙庚", "丙辛", "丁壬", "戊癸");
    private static final List<String> THREE_HARMONIES =
        List.of("申子辰", "亥卯未", "寅午戌", "巳酉丑");
    private static final Map<String, String> BRANCH_ELEMENTS = Map.ofEntries(
        Map.entry("寅", "木"), Map.entry("卯", "木"),
        Map.entry("巳", "火"), Map.entry("午", "火"),
        Map.entry("辰", "土"), Map.entry("戌", "土"),
        Map.entry("丑", "土"), Map.entry("未", "土"),
        Map.entry("申", "金"), Map.entry("酉", "金"),
        Map.entry("亥", "水"), Map.entry("子", "水")
    );

    FortuneAnalysis analyze(BaziChart chart, int fortuneYear) {
        String fortunePillar = Solar.fromYmdHms(fortuneYear, 7, 1, 12, 0, 0)
            .getLunar().getYearInGanZhiExact();
        String fortuneStem = fortunePillar.substring(0, 1);
        String fortuneBranch = fortunePillar.substring(1, 2);
        List<String> relations = new ArrayList<>();

        for (PillarDetails original : chart.pillars()) {
            String branchPair = canonicalPair(original.branch(), fortuneBranch);
            if (BRANCH_COMBINES.contains(branchPair)) {
                relations.add(fortuneBranch + "与" + original.pillar() + "六合");
            }
            if (BRANCH_CLASHES.contains(branchPair)) {
                relations.add(fortuneBranch + "与" + original.pillar() + "相冲");
            }
            if (BRANCH_HARMS.contains(branchPair)) {
                relations.add(fortuneBranch + "与" + original.pillar() + "相害");
            }
            if (original.branch().equals(fortuneBranch)
                && Set.of("辰", "午", "酉", "亥").contains(fortuneBranch)) {
                relations.add(fortuneBranch + "与原局同支，按传统规则视为自刑提示");
            }
            if (STEM_COMBINES.contains(canonicalPair(original.stem(), fortuneStem))) {
                relations.add(fortuneStem + "与" + original.stem() + "天干五合");
            }
        }
        addThreeHarmonyRelations(chart, fortuneBranch, relations);
        if (relations.isEmpty()) relations.add("流年与原局未形成当前规则覆盖的明显合冲刑害");

        List<String> hints = buildHints(chart, fortuneStem, fortuneBranch, relations);
        return new FortuneAnalysis(
            fortuneYear,
            fortunePillar,
            BaziCalculator.elementOfStem(fortuneStem),
            BRANCH_ELEMENTS.getOrDefault(fortuneBranch, ""),
            List.copyOf(relations),
            hints
        );
    }

    private void addThreeHarmonyRelations(BaziChart chart, String fortuneBranch,
                                           List<String> relations) {
        Set<String> originalBranches = chart.pillars().stream()
            .map(PillarDetails::branch)
            .collect(java.util.stream.Collectors.toSet());
        for (String group : THREE_HARMONIES) {
            if (!group.contains(fortuneBranch)) continue;
            long matches = group.codePoints()
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .filter(branch -> branch.equals(fortuneBranch) || originalBranches.contains(branch))
                .distinct()
                .count();
            if (matches == 3) relations.add("流年与原局构成" + group + "三合局");
        }
    }

    private List<String> buildHints(BaziChart chart, String fortuneStem, String fortuneBranch,
                                    List<String> relations) {
        List<String> hints = new ArrayList<>();
        String stemElement = BaziCalculator.elementOfStem(fortuneStem);
        String branchElement = BRANCH_ELEMENTS.getOrDefault(fortuneBranch, "");
        int minimum = chart.visibleFiveElementCounts().values().stream()
            .min(Integer::compareTo).orElse(0);
        if (chart.visibleFiveElementCounts().getOrDefault(stemElement, 0) == minimum
            || chart.visibleFiveElementCounts().getOrDefault(branchElement, 0) == minimum) {
            hints.add("流年五行补充了原局表层计数中相对较少的元素，可关注学习和调整带来的新机会");
        }
        if (relations.stream().anyMatch(item -> item.contains("相冲") || item.contains("相害"))) {
            hints.add("传统关系中存在冲或害，宜把它理解为变化和磨合提示，重要决定应以现实信息为准");
        }
        if (relations.stream().anyMatch(item -> item.contains("六合")
            || item.contains("三合") || item.contains("五合"))) {
            hints.add("传统关系中出现合，适合关注合作、沟通与资源整合主题");
        }
        if (hints.isEmpty()) {
            hints.add("当前规则未显示特别突出的关系，适合保持稳定节奏并结合现实目标安排计划");
        }
        return List.copyOf(hints);
    }

    private String canonicalPair(String first, String second) {
        String direct = first + second;
        String reverse = second + first;
        if (BRANCH_COMBINES.contains(direct) || BRANCH_CLASHES.contains(direct)
            || BRANCH_HARMS.contains(direct) || STEM_COMBINES.contains(direct)) {
            return direct;
        }
        return reverse;
    }
}
