package com.clawbot.wechatbot.tools.bazitool.model;

import java.util.List;

/** Details of one of the four pillars. */
public record PillarDetails(
    String pillar,
    String stem,
    String branch,
    String fiveElements,
    String naYin,
    String stemTenGod,
    List<String> branchTenGods
) {
}
