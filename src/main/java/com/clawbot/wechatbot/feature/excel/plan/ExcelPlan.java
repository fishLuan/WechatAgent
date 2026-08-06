package com.clawbot.wechatbot.feature.excel.plan;

import java.util.List;

/** 一次技能执行对应的完整操作计划：operations 按序执行。 */
public record ExcelPlan(String userId, List<ExcelOperation> operations) {
    public ExcelPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
