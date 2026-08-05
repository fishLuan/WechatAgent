package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库别名解析器：把指令里的列别名解析为表内真实列名——精确/模糊匹配都找不到时，
 * 按知识库字段映射（resolveColumnAlias）替换参数，并在 notes 中记录映射说明供回复标注。
 * RAG 服务为空（旧构造器/回归场景）时计划原样返回，行为不变。
 */
public final class KnowledgeAliasResolver {

    /** 计划解析结果：替换列名后的计划 + 知识库映射说明。 */
    public record ResolvedPlan(ExcelPlan plan, List<String> notes) {
    }

    /** 需要别名解析的列类参数 key（覆盖排序/分组/汇总/图表等操作的列参数）。 */
    private static final List<String> COLUMN_PARAM_KEYS =
        List.of("column", "groupColumn", "valueColumn", "categoryColumn");

    private final ExcelRagService excelRagService;

    public KnowledgeAliasResolver(ExcelRagService excelRagService) {
        this.excelRagService = excelRagService;
    }

    /** 对每个操作的列类参数尝试知识库映射；未命中、RAG 为空或表格为空（工作簿管理指令）时计划原样返回。 */
    public ResolvedPlan resolve(ExcelPlan plan, ExcelTable table) {
        if (excelRagService == null || plan == null || plan.operations().isEmpty()
            || table == null) {
            return new ResolvedPlan(plan, List.of());
        }
        List<String> notes = new ArrayList<>();
        List<ExcelOperation> resolved = new ArrayList<>();
        for (ExcelOperation operation : plan.operations()) {
            Map<String, String> params = operation.params();
            Map<String, String> newParams = null; // 仅当有替换时才新建
            for (String key : COLUMN_PARAM_KEYS) {
                String term = params.get(key);
                if (term == null || term.isBlank()) {
                    continue;
                }
                // 表内精确/模糊匹配已命中则无需知识库介入（RAG 的价值在模糊匹配命中不了时）
                if (ExcelService.findColumnIndex(table.getHeaders(), term) >= 0) {
                    continue;
                }
                String standard = excelRagService.resolveColumnAlias(term);
                if (standard == null || standard.equals(term)) {
                    continue;
                }
                if (newParams == null) {
                    newParams = new LinkedHashMap<>(params);
                }
                newParams.put(key, standard);
                notes.add("📚 已按知识库将「" + term + "」映射为「" + standard + "」");
            }
            resolved.add(newParams == null ? operation
                : new ExcelOperation(operation.id(), operation.type(), newParams,
                    operation.dependsOn()));
        }
        if (notes.isEmpty()) {
            return new ResolvedPlan(plan, List.of());
        }
        return new ResolvedPlan(new ExcelPlan(plan.userId(), List.copyOf(resolved)), notes);
    }
}
