package com.clawbot.wechatbot.feature.excel.plan;

import java.util.List;
import java.util.Map;

/**
 * 单个表格操作：id 供操作间依赖引用（dependsOn 当前恒空，为将来复合任务预留）；
 * params 只允许白名单 key（由 ExcelPlanValidator 校验）。
 */
public record ExcelOperation(
    String id,
    ExcelOperationType type,
    Map<String, String> params,
    List<String> dependsOn
) {
    public ExcelOperation {
        params = params == null ? Map.of() : Map.copyOf(params);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    /** 取参数值；key 不存在时返回 null。 */
    public String param(String key) {
        return params.get(key);
    }
}
