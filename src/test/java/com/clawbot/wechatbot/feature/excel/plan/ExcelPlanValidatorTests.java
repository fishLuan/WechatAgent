package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 计划校验测试：非法行号、未知参数 key、空表头、回滚无版本各自返回统一中文错误。 */
class ExcelPlanValidatorTests {

    private final ExcelService excelService = mock(ExcelService.class);
    private final ExcelPlanValidator validator = new ExcelPlanValidator(excelService);

    private ExcelPlan plan(ExcelOperation... operations) {
        return new ExcelPlan("user-1", List.of(operations));
    }

    private ExcelOperation op(ExcelOperationType type, Map<String, String> params) {
        return new ExcelOperation("1", type, params, List.of());
    }

    /** 已有 2 列 1 行数据的表格。 */
    private ExcelTable existingTable() {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(List.of(List.of("李四", "30")));
        return table;
    }

    @Test
    void outOfRangeRowNumberReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.DELETE_ROW, Map.of("rowNumber", "99")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("行号超出范围"));
        assertTrue(error.get().contains("1"));
    }

    @Test
    void invalidRowNumberTextReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.UPDATE_ROW,
            Map.of("rowNumber", "abc", "cells", "张三")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("非法计划"));
    }

    @Test
    void unknownParamKeyReturnsIllegalPlanError() {
        ExcelPlan plan = plan(op(ExcelOperationType.ADD_ROW,
            Map.of("cells", "张三", "hack", "x")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("非法计划"));
        assertTrue(error.get().contains("hack"));
    }

    @Test
    void missingRequiredParamKeyReturnsIllegalPlanError() {
        ExcelPlan plan = plan(op(ExcelOperationType.QUERY, Map.of("column", "金额")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("非法计划"));
        assertTrue(error.get().contains("queryType"));
    }

    @Test
    void emptyCreateTableHeadersReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.CREATE_TABLE,
            Map.of("headers", "", "rows", "", "overwrite", "false", "title", "我的表格")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("没有可用的表格数据"));
    }

    /** 已有非空数据且指令未带「覆盖」→ 拦截（文案带正确示例）。 */
    @Test
    void createTableWithoutOverwriteOnExistingTableIsBlocked() {
        ExcelPlan plan = plan(op(ExcelOperationType.CREATE_TABLE,
            Map.of("headers", "姓名,城市", "rows", "", "overwrite", "false", "title", "我的表格")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("覆盖"));
        assertTrue(error.get().contains("生成覆盖表格"));
    }

    @Test
    void createTableWithOverwriteOnExistingTablePasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.CREATE_TABLE,
            Map.of("headers", "姓名,城市", "rows", "张三,北京", "overwrite", "true",
                "title", "我的表格")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void rollbackWithoutVersionsReturnsError() {
        ExcelTable table = existingTable();
        when(excelService.versionCount(table)).thenReturn(0L);
        ExcelPlan plan = plan(op(ExcelOperationType.ROLLBACK, Map.of()));
        Optional<String> error = validator.validate(plan, table);
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("没有可回滚"));
    }

    @Test
    void rollbackWithVersionsPasses() {
        ExcelTable table = existingTable();
        when(excelService.versionCount(table)).thenReturn(1L);
        ExcelPlan plan = plan(op(ExcelOperationType.ROLLBACK, Map.of()));
        assertEquals(Optional.empty(), validator.validate(plan, table));
    }

    @Test
    void rowOperationOnEmptyTableReturnsRequireTableError() {
        ExcelTable table = new ExcelTable("user-1", "空表");
        ExcelPlan plan = plan(op(ExcelOperationType.DELETE_ROW, Map.of("rowNumber", "1")));
        Optional<String> error = validator.validate(plan, table);
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("还没有生成表格"));
    }

    @Test
    void invalidQueryTypeReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.QUERY,
            Map.of("column", "金额", "queryType", "MAXIMUM")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("非法计划"));
    }

    @Test
    void validQueryPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.QUERY,
            Map.of("column", "金额", "queryType", "SUM")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void emptyPlanReturnsIllegalPlanError() {
        Optional<String> error = validator.validate(new ExcelPlan("user-1", List.of()),
            existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("非法计划"));
    }

    // ============================
    // 排序校验
    // ============================
    @Test
    void invalidSortDirectionReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.SORT,
            Map.of("column", "年龄", "direction", "UP")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("direction"));
        assertTrue(error.get().contains("ASC/DESC"));
    }

    @Test
    void sortWithUnknownColumnReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.SORT,
            Map.of("column", "不存在", "direction", "ASC")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("找不到列「不存在」"));
        assertTrue(error.get().contains("姓名"));
    }

    @Test
    void validSortPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.SORT,
            Map.of("column", "年龄", "direction", "DESC")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    // ============================
    // 去重校验
    // ============================
    @Test
    void deduplicateWithoutColumnPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.DEDUPLICATE, Map.of()));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void deduplicateWithUnknownColumnReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.DEDUPLICATE, Map.of("column", "不存在")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("找不到列"));
    }

    @Test
    void deduplicateWithExistingColumnPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.DEDUPLICATE, Map.of("column", "姓名")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    // ============================
    // 分组汇总校验
    // ============================
    @Test
    void invalidAggregateReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "姓名", "valueColumn", "年龄", "aggregate", "PRODUCT")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("aggregate"));
        assertTrue(error.get().contains("SUM/AVERAGE/MAX/MIN/COUNT"));
    }

    @Test
    void groupSummaryMissingValueColumnOnNonCountReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "姓名", "aggregate", "SUM")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("valueColumn"));
    }

    @Test
    void groupSummaryCountWithoutValueColumnPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "姓名", "aggregate", "COUNT")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void groupSummaryUnknownGroupColumnReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "不存在", "valueColumn", "年龄", "aggregate", "SUM")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("找不到列"));
    }

    @Test
    void ratioWithNonSumAggregateReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "姓名", "valueColumn", "年龄", "aggregate", "AVERAGE",
                "includeRatio", "true")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("占比"));
    }

    @Test
    void invalidIncludeRatioValueReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "姓名", "valueColumn", "年龄", "aggregate", "SUM",
                "includeRatio", "yes")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("includeRatio"));
    }

    @Test
    void validGroupSummaryWithRatioPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.GROUP_SUMMARY,
            Map.of("groupColumn", "姓名", "valueColumn", "年龄", "aggregate", "SUM",
                "includeRatio", "true")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    // ============================
    // 缺失补全校验
    // ============================
    @Test
    void fillMissingWithUnknownColumnReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.FILL_MISSING,
            Map.of("column", "不存在", "value", "未知")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("找不到列"));
    }

    @Test
    void validFillMissingPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.FILL_MISSING,
            Map.of("column", "姓名", "value", "未知")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    // ============================
    // 知识管理指令校验
    // ============================
    @Test
    void knowledgeAddWithUnknownCategoryReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.KNOWLEDGE_ADD,
            Map.of("category", "随便", "content", "营收→营业收入")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("随便"));
        assertTrue(error.get().contains("FIELD_MAPPING"));
    }

    @Test
    void knowledgeAddWithBlankContentReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.KNOWLEDGE_ADD,
            Map.of("category", "FIELD_MAPPING", "content", "  ")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("content"));
    }

    @Test
    void validKnowledgeAddPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.KNOWLEDGE_ADD,
            Map.of("category", "FIELD_MAPPING", "content", "营收→营业收入")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    /** 知识管理操作不依赖表格状态：空表也能查看知识。 */
    @Test
    void knowledgeListPassesOnEmptyTable() {
        ExcelPlan plan = plan(op(ExcelOperationType.KNOWLEDGE_LIST, Map.of()));
        assertEquals(Optional.empty(), validator.validate(plan, new ExcelTable("user-1", "空表")));
    }

    @Test
    void knowledgeDeleteWithBlankKeywordReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.KNOWLEDGE_DELETE, Map.of("keyword", "")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("keyword"));
    }

    @Test
    void validKnowledgeDeletePasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.KNOWLEDGE_DELETE, Map.of("keyword", "营收")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    // ============================
    // 分析操作在空表上要求先生成表格
    // ============================
    @Test
    void analysisOperationOnEmptyTableReturnsRequireTableError() {
        ExcelTable table = new ExcelTable("user-1", "空表");
        ExcelPlan plan = plan(op(ExcelOperationType.SORT,
            Map.of("column", "年龄", "direction", "ASC")));
        Optional<String> error = validator.validate(plan, table);
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("还没有生成表格"));
    }

    // ============================
    // 工作簿管理指令校验（白名单/必填；不依赖表格状态）
    // ============================
    @Test
    void validWorkbookCreatePasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.WORKBOOK_CREATE, Map.of("title", "销售表")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void workbookCreateMissingTitleReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.WORKBOOK_CREATE, Map.of()));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("title"));
    }

    @Test
    void workbookSelectMissingNameReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.WORKBOOK_SELECT, Map.of()));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("name"));
    }

    @Test
    void workbookRenameMissingNewTitleReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.WORKBOOK_RENAME, Map.of("name", "销售表")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("newTitle"));
    }

    @Test
    void validWorkbookRenamePasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.WORKBOOK_RENAME,
            Map.of("name", "销售表", "newTitle", "月度销售")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    /** 工作簿管理操作不依赖表格状态：空表也能列表/选择/删除/复制。 */
    @Test
    void workbookManagementOpsPassOnEmptyTable() {
        ExcelTable empty = new ExcelTable("user-1", "空表");
        assertEquals(Optional.empty(), validator.validate(
            plan(op(ExcelOperationType.WORKBOOK_LIST, Map.of())), empty));
        assertEquals(Optional.empty(), validator.validate(
            plan(op(ExcelOperationType.WORKBOOK_SELECT, Map.of("name", "销售表"))), empty));
        assertEquals(Optional.empty(), validator.validate(
            plan(op(ExcelOperationType.WORKBOOK_DELETE, Map.of("name", "销售表"))), empty));
        assertEquals(Optional.empty(), validator.validate(
            plan(op(ExcelOperationType.WORKBOOK_COPY, Map.of("name", "销售表"))), empty));
    }

    @Test
    void workbookOpsUnknownParamReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.WORKBOOK_DELETE,
            Map.of("name", "销售表", "hack", "x")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("hack"));
    }

    // ============================
    // 表格式化/图表/汇总页校验
    // ============================
    @Test
    void formatTableWithUnknownParamReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.FORMAT_TABLE,
            Map.of("title", "销售报表", "hack", "x")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("hack"));
    }

    @Test
    void formatTableNonTrueToggleReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.FORMAT_TABLE, Map.of("freezeHeader", "yes")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("freezeHeader"));
    }

    @Test
    void validFormatTablePasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.FORMAT_TABLE,
            Map.of("title", "销售报表", "freezeHeader", "true", "autoFilter", "true")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void formatTableOnEmptyTableReturnsRequireTableError() {
        ExcelPlan plan = plan(op(ExcelOperationType.FORMAT_TABLE, Map.of("freezeHeader", "true")));
        Optional<String> error = validator.validate(plan, new ExcelTable("user-1", "空表"));
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("还没有生成表格"));
    }

    @Test
    void chartMissingRequiredParamsReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.CHART,
            Map.of("chartType", "BAR", "categoryColumn", "产品名称")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("valueColumn"));
    }

    @Test
    void chartInvalidTypeReturnsError() {
        ExcelPlan plan = plan(op(ExcelOperationType.CHART,
            Map.of("chartType", "PIE_CHART", "categoryColumn", "产品名称",
                "valueColumn", "销售额")));
        Optional<String> error = validator.validate(plan, existingTable());
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("BAR/LINE/PIE"));
    }

    @Test
    void validChartPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.CHART,
            Map.of("chartType", "BAR", "categoryColumn", "产品名称", "valueColumn", "销售额")));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }

    @Test
    void dashboardOnEmptyTableReturnsRequireTableError() {
        ExcelPlan plan = plan(op(ExcelOperationType.DASHBOARD, Map.of()));
        Optional<String> error = validator.validate(plan, new ExcelTable("user-1", "空表"));
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("还没有生成表格"));
    }

    @Test
    void validDashboardPasses() {
        ExcelPlan plan = plan(op(ExcelOperationType.DASHBOARD, Map.of()));
        assertEquals(Optional.empty(), validator.validate(plan, existingTable()));
    }
}
