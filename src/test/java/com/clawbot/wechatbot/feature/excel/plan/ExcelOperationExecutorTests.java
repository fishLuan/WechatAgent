package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
import static org.mockito.Mockito.inOrder;
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
        new SortHandler(excelService),
        new DeduplicateHandler(excelService),
        new GroupSummaryHandler(excelService),
        new FillMissingHandler(excelService),
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
        when(excelService.restoreLatestVersion(table)).thenReturn(null);

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.ROLLBACK, Map.of())), table);

        assertFalse(result.success());
        assertTrue(result.text().contains("没有可回滚"));
        verify(excelService, never()).save(table);
    }

    @Test
    void sortDescendingKeepsEmptyValuesLast() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(new ArrayList<>(List.of(
            List.of("王五", ""), List.of("张三", "25"), List.of("李四", "30"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.SORT,
                Map.of("column", "年龄", "direction", "DESC"))), table);

        assertTrue(result.success());
        // 降序排列，但空值仍应排最后
        assertEquals(List.of("李四", "张三", "王五"),
            table.getRows().stream().map(row -> row.get(0)).toList());
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

    // ============================
    // 排序：数值优先、稳定、空值最后、升降序
    // ============================
    @Test
    void sortByNumericColumnAscendingIsStable() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "30"),
            List.of("王五", "20"), List.of("赵六", "25"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "年龄", "direction", "ASC"))), table);

        assertTrue(result.success());
        // 数值升序；相同值（25）保持原顺序（张三在赵六前）
        assertEquals(List.of(
            List.of("王五", "20"), List.of("张三", "25"),
            List.of("赵六", "25"), List.of("李四", "30")), table.getRows());
        assertTrue(result.text().contains("已按年龄排序"));
        assertTrue(result.text().contains("升序"));
        assertNotNull(result.attachment());
        verify(excelService).snapshotVersion(table, "按年龄排序");
        verify(excelService).save(table);
    }

    @Test
    void sortDescendingReversesOrder() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "30"), List.of("王五", "20"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "年龄", "direction", "DESC"))), table);

        assertEquals(List.of(
            List.of("李四", "30"), List.of("张三", "25"), List.of("王五", "20")),
            table.getRows());
    }

    @Test
    void sortTextColumnByUnicodeOrder() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "30"), List.of("王五", "20"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "姓名", "direction", "ASC"))), table);

        // Unicode 顺序：张(U+5F20) < 李(U+674E) < 王(U+738B)
        assertEquals(List.of(
            List.of("张三", "25"), List.of("李四", "30"), List.of("王五", "20")),
            table.getRows());
    }

    @Test
    void sortKeepsEmptyCellsLastInBothDirections() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", ""), List.of("李四", "30"), List.of("王五", "20"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "年龄", "direction", "ASC"))), table);
        assertEquals(List.of(
            List.of("王五", "20"), List.of("李四", "30"), List.of("张三", "")),
            table.getRows());

        executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "年龄", "direction", "DESC"))), table);
        // 降序时空值仍恒排最后
        assertEquals(List.of(
            List.of("李四", "30"), List.of("王五", "20"), List.of("张三", "")),
            table.getRows());
    }

    @Test
    void sortPutsNumericCellsBeforeTextCells() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("A", "abc"), List.of("B", "10"), List.of("C", "5"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "年龄", "direction", "ASC"))), table);

        // 数值（5、10）排在文本（abc）之前
        assertEquals(List.of(
            List.of("C", "5"), List.of("B", "10"), List.of("A", "abc")),
            table.getRows());
    }

    // ============================
    // 去重：整行/按列、保留首次出现、计数
    // ============================
    @Test
    void deduplicateWholeRowKeepsFirstOccurrence() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "30"), List.of("张三", "25"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.DEDUPLICATE, Map.of())), table);

        assertTrue(result.success());
        assertEquals(List.of(List.of("张三", "25"), List.of("李四", "30")), table.getRows());
        assertEquals("✅ 已删除 1 行重复数据（剩 2 行）。", result.text());
        assertNotNull(result.attachment());
        verify(excelService).snapshotVersion(table, "整行去重");
        verify(excelService).save(table);
    }

    @Test
    void deduplicateByColumnKeepsFirstOccurrence() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "25"), List.of("王五", "30"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.DEDUPLICATE, Map.of("column", "年龄"))), table);

        assertTrue(result.success());
        // 按年龄去重：25 重复，保留首行（张三）
        assertEquals(List.of(List.of("张三", "25"), List.of("王五", "30")), table.getRows());
        assertEquals("✅ 已删除 1 行重复数据（剩 2 行）。", result.text());
        verify(excelService).snapshotVersion(table, "按年龄去重");
    }

    @Test
    void deduplicateWithoutDuplicatesKeepsAllRows() throws Exception {
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "30"), List.of("王五", "20"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.DEDUPLICATE, Map.of())), table);

        assertTrue(result.success());
        assertEquals(3, table.getRows().size());
        assertEquals("✅ 已删除 0 行重复数据（剩 3 行）。", result.text());
    }

    // ============================
    // 分组汇总：SUM/AVERAGE/COUNT/占比、替换表头
    // ============================
    @Test
    void groupSummarySumReplacesTable() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("上海", "200"), List.of("北京", "50"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "valueColumn", "销售额", "aggregate", "SUM"))), table);

        assertTrue(result.success());
        // 汇总结果替换当前表格
        assertEquals(List.of("地区", "销售额(合计)"), table.getHeaders());
        assertEquals(List.of(List.of("北京", "150.00"), List.of("上海", "200.00")),
            table.getRows());
        assertTrue(result.text().contains("已生成汇总表"));
        assertTrue(result.text().contains("可回滚"));
        assertNotNull(result.attachment());
        verify(excelService).snapshotVersion(table, "按地区汇总");
        verify(excelService).save(table);
    }

    @Test
    void groupSummaryAverage() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("北京", "50"), List.of("上海", "200"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "valueColumn", "销售额", "aggregate", "AVERAGE"))), table);

        assertEquals(List.of(List.of("北京", "75.00"), List.of("上海", "200.00")),
            table.getRows());
    }

    @Test
    void groupSummaryCountWithoutValueColumnUsesRowCountColumn() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("北京", "50"), List.of("上海", "200"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "aggregate", "COUNT"))), table);

        assertEquals(List.of("地区", "行数"), table.getHeaders());
        assertEquals(List.of(List.of("北京", "2"), List.of("上海", "1")), table.getRows());
    }

    @Test
    void groupSummaryCountWithValueColumnNamesCountColumn() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "订单"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "单1"), List.of("上海", "单2"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "valueColumn", "订单", "aggregate", "COUNT"))), table);

        assertEquals(List.of("地区", "订单(计数)"), table.getHeaders());
    }

    @Test
    void groupSummaryWithRatioAppendsRatioColumn() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("上海", "200"), List.of("北京", "300"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "valueColumn", "销售额", "aggregate", "SUM",
                    "includeRatio", "true"))), table);

        // 北京 400/600=66.67%，上海 200/600=33.33%
        assertEquals(List.of("地区", "销售额(合计)", "占比"), table.getHeaders());
        assertEquals(List.of(List.of("北京", "400.00", "66.67"),
            List.of("上海", "200.00", "33.33")), table.getRows());
    }

    // ============================
    // 缺失补全：指定值/默认未知、空值计数
    // ============================
    @Test
    void fillMissingFillsEmptyCellsWithExplicitValue() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("姓名", "城市"));
        // 行内单元格需要可变列表：补全操作会原地修改单元格
        table.setRows(new ArrayList<>(List.of(
            new ArrayList<>(List.of("张三", "北京")), new ArrayList<>(List.of("李四", "")),
            new ArrayList<>(List.of("王五", "")))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.FILL_MISSING, Map.of("column", "城市", "value", "未知"))), table);

        assertTrue(result.success());
        assertEquals(List.of(List.of("张三", "北京"), List.of("李四", "未知"),
            List.of("王五", "未知")), table.getRows());
        assertEquals("✅ 已补全城市列 2 个空值。", result.text());
        assertNotNull(result.attachment());
        verify(excelService).snapshotVersion(table, "补全城市列");
        verify(excelService).save(table);
    }

    @Test
    void fillMissingDefaultsToUnknownWhenValueMissing() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("姓名", "城市"));
        table.setRows(new ArrayList<>(List.of(
            new ArrayList<>(List.of("张三", "")), new ArrayList<>(List.of("李四", "北京")))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        executor.execute(plan(
            op(1, ExcelOperationType.FILL_MISSING, Map.of("column", "城市"))), table);

        assertEquals(List.of(List.of("张三", "未知"), List.of("李四", "北京")),
            table.getRows());
    }

    // ============================
    // 回归：变更类操作仍先快照、先导出后保存
    // ============================
    @Test
    void analysisOperationsSnapshotBeforeExportBeforeSave() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(new ArrayList<>(List.of(
            List.of("张三", "25"), List.of("李四", "30"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        InOrder inOrder = inOrder(excelService);

        executor.execute(plan(
            op(1, ExcelOperationType.SORT, Map.of("column", "年龄", "direction", "ASC"))), table);

        // 先快照（可回滚）→ 先导出 → 后保存
        inOrder.verify(excelService).snapshotVersion(table, "按年龄排序");
        inOrder.verify(excelService).toXlsx(table);
        inOrder.verify(excelService).save(table);
    }
}
