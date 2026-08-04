package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 计划校验：按操作类型校验参数与当前表格状态，返回统一中文错误提示。
 * 错误文案与重构前的 SkillResult.failure 保持一致。
 * 拒绝未知参数 key：每种操作只允许白名单 key，为将来 LLM 直接输出 JSON 计划做安全前置。
 */
public final class ExcelPlanValidator {

    /** 每种操作允许的参数 key 白名单。 */
    private static final Map<ExcelOperationType, Set<String>> ALLOWED_PARAM_KEYS = Map.ofEntries(
        Map.entry(ExcelOperationType.CREATE_TABLE, Set.of("headers", "rows", "overwrite", "title")),
        Map.entry(ExcelOperationType.ADD_ROW, Set.of("cells")),
        Map.entry(ExcelOperationType.UPDATE_ROW, Set.of("rowNumber", "cells")),
        Map.entry(ExcelOperationType.DELETE_ROW, Set.of("rowNumber")),
        Map.entry(ExcelOperationType.QUERY, Set.of("column", "queryType")),
        Map.entry(ExcelOperationType.SORT, Set.of("column", "direction")),
        Map.entry(ExcelOperationType.DEDUPLICATE, Set.of("column")),
        Map.entry(ExcelOperationType.GROUP_SUMMARY, Set.of("groupColumn", "valueColumn", "aggregate", "includeRatio")),
        Map.entry(ExcelOperationType.FILL_MISSING, Set.of("column", "value")),
        Map.entry(ExcelOperationType.ROLLBACK, Set.of()),
        Map.entry(ExcelOperationType.VERSION_HISTORY, Set.of()));

    /** 每种操作必填的参数 key。 */
    private static final Map<ExcelOperationType, Set<String>> REQUIRED_PARAM_KEYS = Map.ofEntries(
        Map.entry(ExcelOperationType.CREATE_TABLE, Set.of("headers", "rows", "overwrite", "title")),
        Map.entry(ExcelOperationType.ADD_ROW, Set.of("cells")),
        Map.entry(ExcelOperationType.UPDATE_ROW, Set.of("rowNumber", "cells")),
        Map.entry(ExcelOperationType.DELETE_ROW, Set.of("rowNumber")),
        Map.entry(ExcelOperationType.QUERY, Set.of("column", "queryType")),
        Map.entry(ExcelOperationType.SORT, Set.of("column", "direction")),
        Map.entry(ExcelOperationType.DEDUPLICATE, Set.of()),
        Map.entry(ExcelOperationType.GROUP_SUMMARY, Set.of("groupColumn", "aggregate")),
        Map.entry(ExcelOperationType.FILL_MISSING, Set.of("column", "value")),
        Map.entry(ExcelOperationType.ROLLBACK, Set.of()),
        Map.entry(ExcelOperationType.VERSION_HISTORY, Set.of()));

    private final ExcelService excelService;

    public ExcelPlanValidator(ExcelService excelService) {
        this.excelService = excelService;
    }

    /** 校验整个计划：按序返回第一个错误；计划合法返回 empty。 */
    public Optional<String> validate(ExcelPlan plan, ExcelTable table) {
        if (plan == null || plan.operations().isEmpty()) {
            return Optional.of("非法计划：计划中没有操作。");
        }
        for (ExcelOperation operation : plan.operations()) {
            Optional<String> error = validateOperation(operation, table);
            if (error.isPresent()) return error;
        }
        return Optional.empty();
    }

    /** 校验单个操作：未知参数 key、必填参数、以及按操作类型的业务校验。 */
    public Optional<String> validateOperation(ExcelOperation operation, ExcelTable table) {
        Set<String> allowed = ALLOWED_PARAM_KEYS.get(operation.type());
        for (String key : operation.params().keySet()) {
            if (!allowed.contains(key)) {
                return Optional.of("非法计划：" + operation.type().label() + "操作包含未知参数「"
                    + key + "」，允许的参数：" + String.join("、", allowed) + "。");
            }
        }
        Set<String> required = REQUIRED_PARAM_KEYS.get(operation.type());
        for (String key : required) {
            if (!operation.params().containsKey(key)) {
                return Optional.of("非法计划：" + operation.type().label() + "操作缺少参数「" + key + "」。");
            }
        }
        return switch (operation.type()) {
            case CREATE_TABLE -> validateCreateTable(operation, table);
            case ADD_ROW -> validateRequireTable(table);
            case UPDATE_ROW, DELETE_ROW -> validateRowOperation(operation, table);
            case ROLLBACK -> validateRollback(table);
            case QUERY -> validateQuery(operation);
            case SORT -> validateSort(operation, table);
            case DEDUPLICATE -> validateDeduplicate(operation, table);
            case GROUP_SUMMARY -> validateGroupSummary(operation, table);
            case FILL_MISSING -> validateFillMissing(operation, table);
            case VERSION_HISTORY -> Optional.empty();
        };
    }

    /** 生成表格校验：表头非空；已有非空数据时需显式带「覆盖」才允许替换。 */
    private Optional<String> validateCreateTable(ExcelOperation operation, ExcelTable table) {
        ExcelService.ParsedTable parsed =
            ExcelService.parseTableText(OperationChecks.rebuildContent(operation));
        if (parsed.headers().isEmpty()) {
            return Optional.of(
                "没有可用的表格数据，请提供首行为表头、每行一条的表格内容。");
        }
        if (OperationChecks.hasData(table) && !"true".equals(operation.param("overwrite"))) {
            return Optional.of(
                "❌ 你已经有一张 " + table.getHeaders().size() + "列×"
                    + table.getRows().size() + "行 的表格，直接生成会覆盖原数据，已拦截。"
                    + "确认要替换，请重新发送并在指令中带上「覆盖」二字，例如：\n"
                    + "生成覆盖表格：姓名,城市\n张三,北京\n李四,上海");
        }
        return Optional.empty();
    }

    /** 行号类操作校验：先要求表格存在，再校验行号在 1..rows.size 范围内。 */
    private Optional<String> validateRowOperation(ExcelOperation operation, ExcelTable table) {
        Optional<String> requireTable = validateRequireTable(table);
        if (requireTable.isPresent()) return requireTable;
        String rowNumber = operation.param("rowNumber");
        int row;
        try {
            row = Integer.parseInt(rowNumber);
        } catch (NumberFormatException ignored) {
            return Optional.of("非法计划：行号「" + rowNumber + "」不是有效数字。");
        }
        if (row < 1 || row > table.getRows().size()) {
            return Optional.of("行号超出范围，当前共 " + table.getRows().size() + " 行。");
        }
        if (operation.type() == ExcelOperationType.UPDATE_ROW
            && operation.param("cells").isBlank()) {
            return Optional.of("缺少新数据，格式示例：修改第2行为 张三,25,北京。");
        }
        return Optional.empty();
    }

    /** 回滚校验：先要求表格存在，再校验有可回滚的版本。 */
    private Optional<String> validateRollback(ExcelTable table) {
        Optional<String> requireTable = validateRequireTable(table);
        if (requireTable.isPresent()) return requireTable;
        if (excelService.versionCount(table) == 0) {
            return Optional.of(
                "❌ 没有可回滚的版本。做过「添加/修改/删除/覆盖/导入」操作后"
                    + "会生成版本记录，可回滚到最近一次操作前。");
        }
        return Optional.empty();
    }

    /** 查询校验：queryType 必须是 ExcelService.QueryType 的枚举名。 */
    private Optional<String> validateQuery(ExcelOperation operation) {
        String queryType = operation.param("queryType");
        try {
            ExcelService.QueryType.valueOf(queryType);
        } catch (IllegalArgumentException ignored) {
            return Optional.of(
                "非法计划：queryType「" + queryType + "」无效，应为 MAX/MIN/SUM/AVERAGE/COUNT。");
        }
        return Optional.empty();
    }

    /** 排序校验：先要求表格存在，再校验 direction 取值与列存在性。 */
    private Optional<String> validateSort(ExcelOperation operation, ExcelTable table) {
        Optional<String> requireTable = validateRequireTable(table);
        if (requireTable.isPresent()) return requireTable;
        String direction = operation.param("direction");
        if (!"ASC".equals(direction) && !"DESC".equals(direction)) {
            return Optional.of(
                "非法计划：direction「" + direction + "」无效，应为 ASC/DESC。");
        }
        return validateColumnExists(operation.param("column"), table);
    }

    /** 去重校验：先要求表格存在；指定了列时校验列存在性（column 可缺省，按整行去重）。 */
    private Optional<String> validateDeduplicate(ExcelOperation operation, ExcelTable table) {
        Optional<String> requireTable = validateRequireTable(table);
        if (requireTable.isPresent()) return requireTable;
        return validateColumnExists(operation.param("column"), table);
    }

    /**
     * 分组汇总校验：先要求表格存在，再校验 aggregate 取值、占比约束与列存在性；
     * 统计行数（COUNT）时 valueColumn 允许缺省。
     */
    private Optional<String> validateGroupSummary(ExcelOperation operation, ExcelTable table) {
        Optional<String> requireTable = validateRequireTable(table);
        if (requireTable.isPresent()) return requireTable;
        String aggregate = operation.param("aggregate");
        try {
            ExcelService.QueryType.valueOf(aggregate);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Optional.of(
                "非法计划：aggregate「" + aggregate + "」无效，应为 SUM/AVERAGE/MAX/MIN/COUNT。");
        }
        String includeRatio = operation.param("includeRatio");
        if (includeRatio != null && !"true".equals(includeRatio)) {
            return Optional.of("非法计划：includeRatio「" + includeRatio + "」只能为 true。");
        }
        if ("true".equals(includeRatio) && !"SUM".equals(aggregate)) {
            return Optional.of("占比只能配合合计（SUM）使用，当前聚合为「" + aggregate + "」。");
        }
        Optional<String> groupError = validateColumnExists(operation.param("groupColumn"), table);
        if (groupError.isPresent()) return groupError;
        // 统计行数时允许缺省数值列；指定了数值列则同样校验存在性
        String valueColumn = operation.param("valueColumn");
        if (!"COUNT".equals(aggregate)
            && (valueColumn == null || valueColumn.isBlank())) {
            return Optional.of("分组汇总操作缺少参数「valueColumn」（统计行数时可省略）。");
        }
        return validateColumnExists(valueColumn, table);
    }

    /** 缺失补全校验：先要求表格存在，再校验列存在性。 */
    private Optional<String> validateFillMissing(ExcelOperation operation, ExcelTable table) {
        Optional<String> requireTable = validateRequireTable(table);
        if (requireTable.isPresent()) return requireTable;
        return validateColumnExists(operation.param("column"), table);
    }

    /** 列存在性校验：文案与 queryColumn 保持一致；列名为空视为通过（由必填参数检查兜底）。 */
    private Optional<String> validateColumnExists(String column, ExcelTable table) {
        if (column == null || column.isBlank()) return Optional.empty();
        if (ExcelService.findColumnIndex(table.getHeaders(), column) < 0) {
            return Optional.of("❌ 找不到列「" + column + "」，现有列："
                + String.join("、", table.getHeaders()));
        }
        return Optional.empty();
    }

    /** 表格未生成时的统一提示（文案与 OperationChecks.requireTable 一致）。 */
    private Optional<String> validateRequireTable(ExcelTable table) {
        if (table.getHeaders().isEmpty()) {
            return Optional.of("还没有生成表格，请先提供表头和数据生成表格。");
        }
        return Optional.empty();
    }
}
