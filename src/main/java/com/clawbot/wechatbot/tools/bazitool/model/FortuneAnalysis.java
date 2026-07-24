package com.clawbot.wechatbot.tools.bazitool.model;

import java.util.List;

/** Rule-based, entertainment-only analysis for one traditional fortune year. */
public record FortuneAnalysis(
    int year,
    String pillar,
    String stemElement,
    String branchElement,
    List<String> relations,
    List<String> interpretationHints
) {
}
