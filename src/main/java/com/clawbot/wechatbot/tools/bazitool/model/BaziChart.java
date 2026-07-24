package com.clawbot.wechatbot.tools.bazitool.model;

import java.util.List;
import java.util.Map;

/** Structured result of the deterministic calendar calculation. */
public record BaziChart(
    String solarDateTime,
    String lunarDateTime,
    String zodiac,
    List<PillarDetails> pillars,
    String dayMaster,
    Map<String, Integer> visibleFiveElementCounts
) {
}
