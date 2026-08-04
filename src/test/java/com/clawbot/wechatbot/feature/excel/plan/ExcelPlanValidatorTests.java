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
}
