package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 按计划顺序执行操作；任一操作失败立即返回失败（与现状一致）。 */
public final class ExcelOperationExecutor {

    private final Map<ExcelOperationType, ExcelOperationHandler> handlers;

    public ExcelOperationExecutor(List<ExcelOperationHandler> handlers) {
        Map<ExcelOperationType, ExcelOperationHandler> byType =
            new EnumMap<>(ExcelOperationType.class);
        for (ExcelOperationHandler handler : handlers) {
            byType.put(handler.type(), handler);
        }
        this.handlers = Map.copyOf(byType);
    }

    /** 执行整个计划：先校验 dependsOn 引用，再按序执行；返回最后一个成功操作的结果。 */
    public OperationResult execute(ExcelPlan plan, ExcelTable table) throws Exception {
        if (plan == null || plan.operations().isEmpty()) {
            throw new IllegalArgumentException("非法计划：计划中没有操作。");
        }
        // dependsOn 基本校验：引用了不存在的操作 id 视为非法（当前恒空，为复合任务预留）
        Set<String> ids = plan.operations().stream()
            .map(ExcelOperation::id)
            .collect(Collectors.toSet());
        for (ExcelOperation operation : plan.operations()) {
            for (String dependency : operation.dependsOn()) {
                if (!ids.contains(dependency)) {
                    throw new IllegalArgumentException(
                        "非法计划：操作「" + operation.id() + "」依赖了不存在的操作「"
                            + dependency + "」。");
                }
            }
        }
        OperationResult result = null;
        for (ExcelOperation operation : plan.operations()) {
            ExcelOperationHandler handler = handlers.get(operation.type());
            if (handler == null) {
                throw new IllegalArgumentException(
                    "非法计划：没有支持的操作类型「" + operation.type().label() + "」。");
            }
            result = handler.handle(plan.userId(), operation, table);
            if (!result.success()) {
                return result; // 失败即停
            }
        }
        return result;
    }
}
