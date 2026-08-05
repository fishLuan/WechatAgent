package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/** 执行器测试：按计划顺序执行、任一操作失败立即停止。 */
class ExcelOperationExecutorTests {

    private final ExcelService excelService = mock(ExcelService.class);
    private final ExcelRagService ragService = mock(ExcelRagService.class);
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
        new FormatTableHandler(excelService),
        new ChartHandler(excelService),
        new DashboardHandler(excelService),
        new RollbackHandler(excelService),
        new VersionHistoryHandler(excelService),
        new WorkbookCreateHandler(excelService),
        new WorkbookListHandler(excelService),
        new WorkbookSelectHandler(excelService),
        new WorkbookRenameHandler(excelService),
        new WorkbookDeleteHandler(excelService),
        new WorkbookCopyHandler(excelService),
        new KnowledgeAddHandler(ragService),
        new KnowledgeListHandler(ragService),
        new KnowledgeDeleteHandler(ragService)));

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

    /** 添加知识：解析「触发词→标准列名」写入知识库，回复纯文字、不导出附件。 */
    @Test
    void knowledgeAddHandlerWritesToRagServiceWithoutAttachment() throws Exception {
        ExcelTable table = existingTable();
        when(ragService.upsert(anyString(), anyList(), any(), any(), any()))
            .thenReturn(new ExcelRagService.AddResult(null, false));

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.KNOWLEDGE_ADD,
                Map.of("category", "FIELD_MAPPING", "content", "营收→营业收入"))), table);

        assertTrue(result.success());
        assertTrue(result.text().contains("已添加知识"));
        assertTrue(result.text().contains("字段映射"));
        assertNull(result.attachment());
        verify(ragService).upsert(eq("FIELD_MAPPING"), anyList(), eq("营业收入"), isNull(), isNull());
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
    void groupSummaryThenSortCompositeExecutesEndToEnd() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("华东", "100"), List.of("华北", "50"),
            List.of("华东", "150"), List.of("华北", "80"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "valueColumn", "销售额", "aggregate", "SUM")),
            op(2, ExcelOperationType.SORT,
                Map.of("column", "销售额", "direction", "DESC"))), table);

        assertTrue(result.success());
        // 汇总新建独立表（华东 250、华北 130），原表保留并按销售额降序排序
        assertEquals(List.of("地区", "销售额"), table.getHeaders());
        assertEquals(List.of("华东", "华东", "华北", "华北"),
            table.getRows().stream().map(row -> row.get(0)).toList());
        ExcelTable summary = savedSummaryTable();
        assertEquals(List.of("地区", "销售额(合计)"), summary.getHeaders());
        assertEquals(List.of(List.of("华东", "250.00"), List.of("华北", "130.00")),
            summary.getRows());
    }

    @Test
    void createTableOverwritesAndSnapshotsBefore() throws Exception {
        ExcelTable table = existingTable();
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
    // 分组汇总：SUM/AVERAGE/COUNT/占比，结果新建独立汇总表、原表保留
    // ============================
    @Test
    void groupSummaryCreatesNewWorkbookKeepingOriginal() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("上海", "200"), List.of("北京", "50"))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.GROUP_SUMMARY,
                Map.of("groupColumn", "地区", "valueColumn", "销售额", "aggregate", "SUM"))), table);

        assertTrue(result.success());
        // 原表保持不变
        assertEquals(List.of("地区", "销售额"), table.getHeaders());
        assertEquals(3, table.getRows().size());
        // 汇总结果落在新建的「测试表-汇总」
        ExcelTable summary = savedSummaryTable();
        assertEquals("测试表-汇总", summary.getTitle());
        assertEquals(List.of("地区", "销售额(合计)"), summary.getHeaders());
        assertEquals(List.of(List.of("北京", "150.00"), List.of("上海", "200.00")),
            summary.getRows());
        assertTrue(result.text().contains("已生成汇总表"));
        assertTrue(result.text().contains("原表保持不变"));
        assertNotNull(result.attachment());
        verify(excelService, never()).snapshotVersion(any(), anyString());
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
            savedSummaryTable().getRows());
        assertEquals(List.of("地区", "销售额"), table.getHeaders());
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

        ExcelTable summary = savedSummaryTable();
        assertEquals(List.of("地区", "行数"), summary.getHeaders());
        assertEquals(List.of(List.of("北京", "2"), List.of("上海", "1")), summary.getRows());
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

        assertEquals(List.of("地区", "订单(计数)"), savedSummaryTable().getHeaders());
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
        ExcelTable summary = savedSummaryTable();
        assertEquals(List.of("地区", "销售额(合计)", "占比"), summary.getHeaders());
        assertEquals(List.of(List.of("北京", "400.00", "66.67"),
            List.of("上海", "200.00", "33.33")), summary.getRows());
    }

    /** 抓取本次执行中保存的「汇总」表（新建独立表）。 */
    private ExcelTable savedSummaryTable() {
        ArgumentCaptor<ExcelTable> captor = ArgumentCaptor.forClass(ExcelTable.class);
        verify(excelService, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
            .filter(t -> t.getTitle().endsWith("-汇总"))
            .findFirst().orElseThrow();
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
    // 复合计划：线性依赖链按序执行、失败即停
    // ============================
    @Test
    void compositePlanExecutesStepsInOrderWithLinearDependency() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("姓名", "年龄"));
        // 行内单元格需可变列表：补全操作会原地修改单元格
        table.setRows(new ArrayList<>(List.of(
            new ArrayList<>(List.of("张三", "25")),
            new ArrayList<>(List.of("李四", "")),
            new ArrayList<>(List.of("张三", "25")))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        // 复合计划：先整行去重（张三,25 重复一次），再补全年龄列空值
        OperationResult result = executor.execute(plan(
            new ExcelOperation("1", ExcelOperationType.DEDUPLICATE, Map.of(), List.of()),
            new ExcelOperation("2", ExcelOperationType.FILL_MISSING,
                Map.of("column", "年龄", "value", "未知"), List.of("1"))), table);

        assertTrue(result.success());
        // 两步按序生效：去重剩 2 行，年龄空值补全为「未知」
        assertEquals(List.of(List.of("张三", "25"), List.of("李四", "未知")),
            table.getRows());
        // 返回最后一步（第 2 步）的文案与附件
        assertEquals("✅ 已补全年龄列 1 个空值。", result.text());
        assertNotNull(result.attachment());
        verify(excelService).snapshotVersion(table, "整行去重");
        verify(excelService).snapshotVersion(table, "补全年龄列");
        verify(excelService, times(2)).save(table);
    }

    @Test
    void compositePlanStopsOnMiddleStepFailure() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("姓名", "城市"));
        // 行内单元格需可变列表：补全操作会原地修改单元格
        table.setRows(new ArrayList<>(List.of(
            new ArrayList<>(List.of("张三", "北京")),
            new ArrayList<>(List.of("李四", "")))));
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        // 第 2 步排序的列不存在 → 失败即停，第 3 步不再执行
        OperationResult result = executor.execute(plan(
            new ExcelOperation("1", ExcelOperationType.FILL_MISSING,
                Map.of("column", "城市", "value", "未知"), List.of()),
            new ExcelOperation("2", ExcelOperationType.SORT,
                Map.of("column", "不存在的列", "direction", "ASC"), List.of("1")),
            new ExcelOperation("3", ExcelOperationType.DEDUPLICATE, Map.of(), List.of("2"))), table);

        assertFalse(result.success());
        assertTrue(result.text().contains("找不到列"));
        // 第 1 步已生效并保存，第 3 步未执行
        assertEquals(List.of(List.of("张三", "北京"), List.of("李四", "未知")),
            table.getRows());
        verify(excelService, times(1)).save(table);
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

    // ============================
    // 工作簿管理操作（纯文字回复，不导出附件、不快照）
    // ============================
    @Test
    void workbookCreateHandlerCreatesWorkbookAndReplies() throws Exception {
        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_CREATE, Map.of("title", "销售表"))),
            existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已新建表格「销售表」"));
        assertNull(result.attachment());
        verify(excelService).createWorkbook("user-1", "销售表");
    }

    @Test
    void workbookListHandlerRepliesTitlesAndMarksActive() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        ExcelTable t2 = new ExcelTable("user-1", "周报");
        t2.setId("t2");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1, t2));
        when(excelService.getActiveWorkbook("user-1")).thenReturn(t2);

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_LIST, Map.of())), existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("共 2 张表格"));
        assertTrue(result.text().contains("销售表"));
        assertTrue(result.text().contains("周报（当前）"));
        assertNull(result.attachment());
    }

    @Test
    void workbookListHandlerEmptyReply() throws Exception {
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of());

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_LIST, Map.of())), existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("还没有表格"));
    }

    @Test
    void workbookSelectHandlerSwitchesActiveWorkbook() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1));

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_SELECT, Map.of("name", "销售表"))),
            existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已切换到表格「销售表」"));
        verify(excelService).setActiveWorkbook("user-1", t1);
    }

    /** 选择不存在的表格：明确提示并给出现有列表，不切换。 */
    @Test
    void workbookSelectNotFoundListsExistingTitles() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1));

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_SELECT, Map.of("name", "不存在"))),
            existingTable());

        assertFalse(result.success());
        assertTrue(result.text().contains("找不到表格「不存在」"));
        assertTrue(result.text().contains("销售表"));
    }

    /** 选择同名表格：命中第一张并提示重名。 */
    @Test
    void workbookSelectWithDuplicateTitlesPicksFirstAndNotes() throws Exception {
        ExcelTable first = new ExcelTable("user-1", "销售表");
        first.setId("t1");
        ExcelTable second = new ExcelTable("user-1", "销售表");
        second.setId("t2");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(first, second));

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_SELECT, Map.of("name", "销售表"))),
            existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已切换到表格「销售表」"));
        assertTrue(result.text().contains("2 张同名表格"));
        verify(excelService).setActiveWorkbook("user-1", first);
    }

    @Test
    void workbookRenameHandlerRenamesAndKeepsActive() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        ExcelTable renamed = new ExcelTable("user-1", "月度销售");
        renamed.setId("t1");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1));
        when(excelService.renameWorkbook("user-1", "t1", "月度销售"))
            .thenReturn(Optional.of(renamed));

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_RENAME,
                Map.of("name", "销售表", "newTitle", "月度销售"))), existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已重命名表格「销售表」为「月度销售」"));
        verify(excelService).renameWorkbook("user-1", "t1", "月度销售");
    }

    /** 删除的是活动表：提示已无当前表。 */
    @Test
    void workbookDeleteHandlerDeletesAndNotesNoActiveTable() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1));
        when(excelService.deleteWorkbook("user-1", "t1")).thenReturn(true);
        when(excelService.getActiveWorkbook("user-1")).thenReturn(t1);

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_DELETE, Map.of("name", "销售表"))),
            existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已删除表格「销售表」"));
        assertTrue(result.text().contains("已无当前表"));
        verify(excelService).deleteWorkbook("user-1", "t1");
    }

    /** 删除的不是活动表：正常提示，不提及当前表。 */
    @Test
    void workbookDeleteHandlerKeepsActiveTableMention() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        ExcelTable active = new ExcelTable("user-1", "周报");
        active.setId("t2");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1, active));
        when(excelService.deleteWorkbook("user-1", "t1")).thenReturn(true);
        when(excelService.getActiveWorkbook("user-1")).thenReturn(active);

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_DELETE, Map.of("name", "销售表"))),
            existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已删除表格「销售表」"));
        assertFalse(result.text().contains("已无当前表"));
    }

    @Test
    void workbookCopyHandlerCopiesAndSwitches() throws Exception {
        ExcelTable t1 = new ExcelTable("user-1", "销售表");
        t1.setId("t1");
        ExcelTable copy = new ExcelTable("user-1", "销售表副本");
        copy.setId("t2");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(t1));
        when(excelService.copyWorkbook("user-1", "t1")).thenReturn(Optional.of(copy));

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_COPY, Map.of("name", "销售表"))),
            existingTable());

        assertTrue(result.success());
        assertTrue(result.text().contains("已复制表格「销售表」为「销售表副本」"));
        verify(excelService).copyWorkbook("user-1", "t1");
    }

    /** 删除不存在的表格：明确提示并给出现有列表。 */
    @Test
    void workbookDeleteNotFoundListsExistingTitles() throws Exception {
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of());

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.WORKBOOK_DELETE, Map.of("name", "不存在"))),
            existingTable());

        assertFalse(result.success());
        assertTrue(result.text().contains("找不到表格「不存在」"));
        assertTrue(result.text().contains("还没有表格"));
    }

    // ============================
    // 表格式化：设置字段并快照、先导出后保存
    // ============================
    @Test
    void formatTableHandlerSetsFieldsSnapshotsAndSaves() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.FORMAT_TABLE,
                Map.of("title", "销售报表", "freezeHeader", "true", "autoFilter", "true"))),
            table);

        assertTrue(result.success());
        assertEquals("销售报表", table.getTitleRow());
        assertTrue(table.isFreezeHeader());
        assertTrue(table.isAutoFilter());
        // 回复按实际应用项拼接
        assertTrue(result.text().contains("✅ 已应用格式"));
        assertTrue(result.text().contains("标题"));
        assertTrue(result.text().contains("冻结首行"));
        assertTrue(result.text().contains("自动筛选"));
        assertNotNull(result.attachment());
        verify(excelService).snapshotVersion(table, "设置格式");
        verify(excelService).save(table);
    }

    /** 只应用指定的项：部分参数时其他字段保持原状。 */
    @Test
    void formatTableHandlerAppliesOnlyGivenOptions() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.FORMAT_TABLE, Map.of("freezeHeader", "true"))), table);

        assertTrue(result.success());
        assertTrue(table.isFreezeHeader());
        assertFalse(table.isAutoFilter());
        assertNull(table.getTitleRow());
        assertFalse(result.text().contains("自动筛选"));
        verify(excelService).snapshotVersion(table, "设置格式");
        verify(excelService).save(table);
    }

    // ============================
    // 图表：列校验、数值不足失败；成功时不修改数据、不写快照
    // ============================
    @Test
    void chartHandlerFailsOnMissingCategoryColumn() throws Exception {
        ExcelTable table = existingTable();

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.CHART,
                Map.of("chartType", "BAR", "categoryColumn", "不存在", "valueColumn", "年龄"))),
            table);

        assertFalse(result.success());
        assertTrue(result.text().contains("找不到列「不存在」"));
        verify(excelService, never()).save(table);
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }

    /** 数值列数值不足（少于 2 条）时失败，不触发导出。 */
    @Test
    void chartHandlerFailsWhenNumericValuesInsufficient() throws Exception {
        ExcelTable table = existingTable(); // 仅 1 行数据

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.CHART,
                Map.of("chartType", "BAR", "categoryColumn", "姓名", "valueColumn", "年龄"))),
            table);

        assertFalse(result.success());
        assertTrue(result.text().contains("图表数据不足"));
        verify(excelService, never()).toXlsxWithChart(any(), anyString(), anyString(), anyString());
        verify(excelService, never()).save(table);
    }

    @Test
    void chartHandlerGeneratesChartWithoutSnapshotsOrSave() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("产品", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("A", "100"), List.of("B", "200"), List.of("C", "150"))));
        when(excelService.toXlsxWithChart(eq(table), eq("BAR"), eq("产品"), eq("销售额")))
            .thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.CHART,
                Map.of("chartType", "BAR", "categoryColumn", "产品", "valueColumn", "销售额"))),
            table);

        assertTrue(result.success());
        assertTrue(result.text().contains("✅ 已生成柱状图"));
        assertTrue(result.text().contains("按 产品 统计 销售额"));
        assertTrue(result.text().contains("'图表'"));
        assertNotNull(result.attachment());
        verify(excelService, never()).save(table);
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }

    /** 多图表：extraCharts 生成多张图（一张工作簿多工作表），不落库、不快照。 */
    @Test
    void chartHandlerGeneratesMultipleChartsFromExtraSpecs() throws Exception {
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("华东", "100"), List.of("华北", "50"),
            List.of("华南", "80"), List.of("西北", "60"))));
        when(excelService.toXlsxWithCharts(any(), any())).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.CHART,
                Map.of("chartType", "LINE", "categoryColumn", "地区", "valueColumn", "销售额",
                    "extraCharts", "PIE|地区|销售额"))), table);

        assertTrue(result.success());
        assertTrue(result.text().contains("2 张图表"));
        verify(excelService).toXlsxWithCharts(eq(table),
            argThat(specs -> specs.size() == 2));
        verify(excelService, never()).save(any());
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }

    // ============================
    // 汇总页：纯导出，不修改数据、不写快照
    // ============================
    @Test
    void dashboardHandlerGeneratesWithoutSnapshotsOrSave() throws Exception {
        ExcelTable table = existingTable();
        when(excelService.toXlsxWithDashboard(table)).thenReturn(new byte[]{1, 2, 3});

        OperationResult result = executor.execute(plan(
            op(1, ExcelOperationType.DASHBOARD, Map.of())), table);

        assertTrue(result.success());
        assertEquals("✅ 已生成汇总页。", result.text());
        assertNotNull(result.attachment());
        verify(excelService, never()).save(table);
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }
}
