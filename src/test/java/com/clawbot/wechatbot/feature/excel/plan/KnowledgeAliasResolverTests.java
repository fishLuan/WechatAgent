package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库别名解析器测试：模糊匹配失败时按字段映射替换列参数，并记录映射说明。 */
class KnowledgeAliasResolverTests {

    private final ExcelRagService ragService = mock(ExcelRagService.class);
    private final KnowledgeAliasResolver resolver = new KnowledgeAliasResolver(ragService);

    /** 已有 1 列（营业收入）的表格（「营业额」在表内精确/模糊都找不到）。 */
    private ExcelTable table() {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("营业收入"));
        table.setRows(List.of(List.of("100")));
        return table;
    }

    private ExcelOperation op(ExcelOperationType type, Map<String, String> params) {
        return new ExcelOperation("1", type, params, List.of());
    }

    @Test
    void resolvesAliasForColumnMissingInTable() {
        when(ragService.resolveColumnAlias("营业额")).thenReturn("营业收入");
        ExcelPlan plan = new ExcelPlan("user-1", List.of(
            op(ExcelOperationType.QUERY, Map.of("column", "营业额", "queryType", "SUM"))));

        KnowledgeAliasResolver.ResolvedPlan resolved = resolver.resolve(plan, table());

        assertEquals("营业收入", resolved.plan().operations().get(0).param("column"));
        assertEquals(List.of("📚 已按知识库将「营业额」映射为「营业收入」"), resolved.notes());
    }

    /** 表内精确/模糊已命中的列名不需要知识库介入，也不产生映射说明。 */
    @Test
    void skipsColumnsAlreadyFoundInTable() {
        ExcelPlan plan = new ExcelPlan("user-1", List.of(
            op(ExcelOperationType.QUERY, Map.of("column", "营业收入", "queryType", "SUM"))));

        KnowledgeAliasResolver.ResolvedPlan resolved = resolver.resolve(plan, table());

        assertSame(plan, resolved.plan());
        assertTrue(resolved.notes().isEmpty());
        verify(ragService, never()).resolveColumnAlias(anyString());
    }

    @Test
    void resolvesGroupAndValueColumnsIndependently() {
        when(ragService.resolveColumnAlias("营业额")).thenReturn("营业收入");
        ExcelPlan plan = new ExcelPlan("user-1", List.of(
            op(ExcelOperationType.GROUP_SUMMARY, Map.of(
                "groupColumn", "地区", "valueColumn", "营业额", "aggregate", "SUM"))));

        KnowledgeAliasResolver.ResolvedPlan resolved = resolver.resolve(plan, table());

        ExcelOperation resolvedOp = resolved.plan().operations().get(0);
        // 「地区」未命中知识库，保持原样；「营业额」被映射
        assertEquals("地区", resolvedOp.param("groupColumn"));
        assertEquals("营业收入", resolvedOp.param("valueColumn"));
        assertEquals(List.of("📚 已按知识库将「营业额」映射为「营业收入」"), resolved.notes());
    }

    @Test
    void withoutRagServicePlanIsUnchanged() {
        KnowledgeAliasResolver nullResolver = new KnowledgeAliasResolver(null);
        ExcelPlan plan = new ExcelPlan("user-1", List.of(
            op(ExcelOperationType.QUERY, Map.of("column", "营业额", "queryType", "SUM"))));

        KnowledgeAliasResolver.ResolvedPlan resolved = nullResolver.resolve(plan, table());

        assertSame(plan, resolved.plan());
        assertTrue(resolved.notes().isEmpty());
    }
}
