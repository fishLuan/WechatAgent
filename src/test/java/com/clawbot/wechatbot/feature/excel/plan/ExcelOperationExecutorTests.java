package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 执行器测试：按计划顺序执行、任一操作失败立即停止。 */
class ExcelOperationExecutorTests {

    private final ExcelService excelService = mock(ExcelService.class);
    private final ExcelOperationExecutor executor = new ExcelOperationExecutor(List.of(
        new CreateTableHandler(excelService),
        new AddRowHandler(excelService),
        new UpdateRowHandler(excelService),
        new DeleteRowHandler(excelService),
        new QueryHandler(excelService),
        new RollbackHandler(excelService),
        new VersionHistoryHandler(excelService)));

    private ExcelPlan plan(ExcelOperation... operations) {
        return new ExcelPlan("user-1", List.of(operations));
    }

    private ExcelOperation op(int id, ExcelOperationType type, Map<String, String> params) {
        return new ExcelOperation(String.valueOf(id), type, params, List.of());
    }

    /** 已有 2 列 1 行数据的表格（rows 为可变列表，供执行时断言变更）。 */
    private ExcelTable existingTable() {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(new ArrayList<>(List.of(List.of("张三", "25"))));
        return table;
    }

    // ============================
    // 按序执行
    // ============================
    @Test
    void executesOperationsInOrder() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.ADD_ROW, Map.of("cells", "李四,30")),
            op(2, ExcelOperationType.ADD_ROW, Map.of("cells", "王五,35"))), table);

        assertTrue(result.success());
        // 两条数据按计划顺序依次追加
        assertEquals(List.of(List.of("张三", "25"), List.of("李四", "30"), List.of("王五", "35")),
            table.getRows());
        verify(excelService, times(2)).save(table);
        // 返回最后一个成功操作的结果与附件
        assertEquals("✅ 已添加第 3 行。", result.text());
        assertNotNull(result.attachment());
    }

    @Test
    void queryHandlerReturnsTextWithoutAttachment() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.queryColumn(any(), anyString(), any()))
            .thenReturn("📊 年龄列的合计：55.00（基于 2 个数值）");

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.QUERY, Map.of("column", "年龄", "queryType", "SUM"))), table);

        assertTrue(result.success());
        assertTrue(result.text().contains("年龄列"));
        assertNull(result.attachment());
        verify(excelService, never()).save(table);
    }

    // ============================
    // 失败即停
    // ============================
    @Test
    void stopsOnFirstFailure() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        // 第2个操作行号越界 → 立即失败，第3个操作不再执行
        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.ADD_ROW, Map.of("cells", "李四,30")),
            op(2, ExcelOperationType.UPDATE_ROW, Map.of("rowNumber", "99", "cells", "王五,35")),
            op(3, ExcelOperationType.ADD_ROW, Map.of("cells", "王五,35"))), table);

        assertFalse(result.success());
        assertTrue(result.text().contains("行号超出范围"));
        assertEquals(2, table.getRows().size());
        verify(excelService, times(1)).save(table);
    }

    @Test
    void rollbackHandlerFailureStopsExecution() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.versionCount(table)).thenReturn(1L);
        when(excelService.restoreLatestVersion(table)).thenReturn(false);

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.ROLLBACK, Map.of())), table);

        assertFalse(result.success());
        assertTrue(result.text().contains("没有可回滚"));
        verify(excelService, never()).save(table);
    }

    @Test
    void createTableOverwritesAndSnapshotsBefore() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.loadOrCreate(anyString(), anyString())).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.CREATE_TABLE,
                Map.of("headers", "姓名,城市", "rows", "张三,北京\n李四,上海",
                    "overwrite", "true", "title", "我的表格"))), table);

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
        assertEquals(2, table.getRows().size());
        verify(excelService).snapshotVersion(table, "覆盖生成表格");
        verify(excelService).save(table);
    }

    // ============================
    // dependsOn 基本校验
    // ============================
    @Test
    void dependsOnUnknownOperationIsIllegal() {
        ExcelTable table = existingTable();
        ExcelOperation operation = new ExcelOperation("1", ExcelOperationType.ADD_ROW,
            Map.of("cells", "李四,30"), List.of("missing-2"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> executor.execute(plan(operation), table));
        assertTrue(error.getMessage().contains("非法计划"));
        assertTrue(error.getMessage().contains("missing-2"));
    }

    @Test
    void emptyPlanIsIllegal() {
        ExcelTable table = existingTable();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> executor.execute(new ExcelPlan("user-1", List.of()), table));
        assertTrue(error.getMessage().contains("非法计划"));
    }
}
