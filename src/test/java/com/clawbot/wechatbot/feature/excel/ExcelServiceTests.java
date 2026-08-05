package com.clawbot.wechatbot.feature.excel;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableVersionRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTValAx;
import org.openxmlformats.schemas.drawingml.x2006.chart.STAxPos;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ExcelServiceTests {

    private final ExcelService service = new ExcelService(null, null);

    // ============================
    // 1. 表格文本解析
    // ============================
    @Test
    void parsesCommaSeparatedTable() {
        ExcelService.ParsedTable table =
            ExcelService.parseTableText("姓名,年龄,城市\n张三,25,北京\n李四,30,上海");
        assertEquals(List.of("姓名", "年龄", "城市"), table.headers());
        assertEquals(2, table.rows().size());
        assertEquals(List.of("张三", "25", "北京"), table.rows().get(0));
    }

    @Test
    void parsesTabAndPipeSeparatedTable() {
        ExcelService.ParsedTable tab =
            ExcelService.parseTableText("姓名\t年龄\n张三\t25");
        assertEquals(List.of("姓名", "年龄"), tab.headers());

        ExcelService.ParsedTable pipe =
            ExcelService.parseTableText("姓名|年龄\n张三|25");
        assertEquals(List.of("姓名", "年龄"), pipe.headers());
    }

    @Test
    void skipsBlankLinesAndTrimsCells() {
        ExcelService.ParsedTable table = ExcelService.parseTableText(
            "  姓名,年龄  \n\n  张三 , 25  \n\n");
        assertEquals(List.of("姓名", "年龄"), table.headers());
        assertEquals(1, table.rows().size());
        assertEquals(List.of("张三", "25"), table.rows().get(0));
    }

    @Test
    void alignsCellsToHeaderCount() {
        ExcelService.ParsedTable table = ExcelService.parseTableText(
            "姓名,年龄,城市\n张三,25\n李四,30,上海,浦东");
        assertEquals(3, table.rows().get(0).size());
        assertEquals("", table.rows().get(0).get(2));     // 少列补空
        assertEquals(3, table.rows().get(1).size());      // 多列截断
        assertEquals("上海", table.rows().get(1).get(2));
    }

    // ============================
    // 2. 列聚合查询
    // ============================
    private ExcelTable sampleTable() {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("姓名", "年龄", "工资"));
        table.setRows(List.of(
            List.of("张三", "25", "￥8000"),
            List.of("李四", "30", "10000"),
            List.of("王五", "28", "9,500")));
        return table;
    }

    @Test
    void queriesMaxMinSumAverage() {
        ExcelTable table = sampleTable();
        assertTrue(service.queryColumn(table, "年龄", ExcelService.QueryType.MAX)
            .contains("30"));
        assertTrue(service.queryColumn(table, "年龄", ExcelService.QueryType.MIN)
            .contains("25"));
        assertTrue(service.queryColumn(table, "工资", ExcelService.QueryType.SUM)
            .contains("27500"));
        assertTrue(service.queryColumn(table, "年龄", ExcelService.QueryType.AVERAGE)
            .contains("27.67"));
    }

    @Test
    void queryCountReturnsRowCount() {
        ExcelTable table = sampleTable();
        assertTrue(service.queryColumn(table, "姓名", ExcelService.QueryType.COUNT)
            .contains("3"));
    }

    @Test
    void queryMissingColumnReturnsError() {
        ExcelTable table = sampleTable();
        assertTrue(service.queryColumn(table, "不存在", ExcelService.QueryType.MAX)
            .contains("找不到列"));
    }

    @Test
    void queryTextColumnReturnsError() {
        ExcelTable table = sampleTable();
        assertTrue(service.queryColumn(table, "姓名", ExcelService.QueryType.MAX)
            .contains("没有可计算的数值"));
    }

    // ============================
    // 3. POI 导出 .xlsx（读回验证）
    // ============================
    @Test
    void exportsXlsxReadableByPoi() throws Exception {
        ExcelTable table = sampleTable();
        byte[] bytes = service.toXlsx(table);
        assertTrue(bytes.length > 0);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("测试表", sheet.getSheetName());
            Row header = sheet.getRow(0);
            assertEquals("姓名", header.getCell(0).getStringCellValue());
            assertEquals("年龄", header.getCell(1).getStringCellValue());
            assertEquals(4, sheet.getLastRowNum() + 1); // 表头 + 3 行数据
        }
    }

    @Test
    void emptyTableExportsEmptySheet() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "空表");
        byte[] bytes = service.toXlsx(table);
        assertTrue(bytes.length > 0);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertTrue(workbook.getNumberOfSheets() >= 1);
        }
    }

    @Test
    void keepsEmptyCellsInRow() {
        ExcelService.ParsedTable table = ExcelService.parseTableText(
            "姓名,年龄,城市\n张三,,北京");
        assertEquals(3, table.headers().size());
        assertEquals(1, table.rows().size());
        assertEquals(List.of("张三", "", "北京"), table.rows().get(0));
    }

    @Test
    void keepsTrailingEmptyCells() {
        ExcelService.ParsedTable table = ExcelService.parseTableText(
            "姓名,年龄,城市\n张三,25,");
        assertEquals(3, table.rows().get(0).size());
        assertEquals(List.of("张三", "25", ""), table.rows().get(0));
    }

    @Test
    void splitRowDataKeepsEmptyCells() {
        ExcelTable table = new ExcelTable("user-1", "测试表");
        table.setHeaders(List.of("姓名", "年龄", "城市"));
        assertEquals(List.of("王五", "", "广州"),
            ExcelService.splitRowData("王五,,广州", table));
    }

    // ============================
    // 4. 单元格类型化（导出时推断，存储仍为字符串）
    // ============================
    @Test
    void exportsNumbersTextAndEmptyCellsWithInference() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "类型表");
        table.setHeaders(List.of("整数", "小数", "前导零", "尾零", "文本", "空"));
        table.setRows(List.of(List.of("42", "3.14", "007", "1.50", "abc", "")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.NUMERIC, row.getCell(0).getCellType());
            assertEquals(42.0, row.getCell(0).getNumericCellValue(), 0.0001);
            assertEquals(CellType.NUMERIC, row.getCell(1).getCellType());
            assertEquals(3.14, row.getCell(1).getNumericCellValue(), 0.0001);
            // 前导零文本：往返校验不一致，保持文本，不得变成数字 7
            assertEquals(CellType.STRING, row.getCell(2).getCellType());
            assertEquals("007", row.getCell(2).getStringCellValue());
            // 尾零文本：往返校验不一致，保持文本，不得变成 1.5
            assertEquals(CellType.STRING, row.getCell(3).getCellType());
            assertEquals("1.50", row.getCell(3).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(4).getCellType());
            assertEquals("abc", row.getCell(4).getStringCellValue());
            // 空字符串按空单元格处理
            assertEquals(CellType.BLANK, row.getCell(5).getCellType());
        }
    }

    @Test
    void exportsBooleansAndUnambiguousDates() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "类型表");
        table.setHeaders(List.of("布尔", "日期", "日期2", "日期3", "非日期"));
        table.setRows(List.of(List.of(
            "true", "2024-01-05", "2024/01/05", "2024年1月5日", "2024年1月")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.BOOLEAN, row.getCell(0).getCellType());
            assertTrue(row.getCell(0).getBooleanCellValue());
            assertDateCell(row.getCell(1), LocalDate.of(2024, 1, 5));
            assertDateCell(row.getCell(2), LocalDate.of(2024, 1, 5));
            assertDateCell(row.getCell(3), LocalDate.of(2024, 1, 5));
            // 不完整的日期格式一律按文本
            assertEquals(CellType.STRING, row.getCell(4).getCellType());
            assertEquals("2024年1月", row.getCell(4).getStringCellValue());
        }
    }

    /** 断言单元格是日期格式且值等于预期日期。 */
    private static void assertDateCell(Cell cell, LocalDate expected) {
        assertTrue(DateUtil.isCellDateFormatted(cell));
        assertEquals(expected, cell.getLocalDateTimeCellValue().toLocalDate());
    }

    // ============================
    // 5. 货币精度（SUM/AVERAGE 用 BigDecimal）
    // ============================
    @Test
    void sumAverageAvoidFloatPrecisionTraces() {
        ExcelTable table = new ExcelTable("user-1", "金额表");
        table.setHeaders(List.of("金额"));
        table.setRows(List.of(List.of("0.1"), List.of("0.2")));
        assertTrue(service.queryColumn(table, "金额", ExcelService.QueryType.SUM)
            .contains("0.30"));
        assertTrue(service.queryColumn(table, "金额", ExcelService.QueryType.AVERAGE)
            .contains("0.15"));
    }

    @Test
    void sumKeepsTwoDecimalsFormat() {
        ExcelTable table = sampleTable();
        assertTrue(service.queryColumn(table, "工资", ExcelService.QueryType.SUM)
            .contains("27500.00"));
    }

    // ============================
    // 6. 版本快照与回滚
    // ============================
    private ExcelService versionedService(FakeVersionRepository fake) {
        return new ExcelService(null, fake);
    }

    @Test
    void snapshotVersionIsIsolatedFromLaterTableMutation() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三")));

        service.snapshotVersion(table, "添加第1行");
        // 修改原表不影响快照内容
        table.getHeaders().add("年龄");
        table.getRows().add(List.of("李四"));

        assertEquals(1, service.versionCount(table));
        ExcelTableVersion version = service.recentVersions(table, 10).get(0);
        assertEquals(List.of("姓名"), version.getHeaders());
        assertEquals(List.of(List.of("张三")), version.getRows());
    }

    @Test
    void restoreLatestVersionRollsBackToSnapshot() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(List.of(List.of("张三", "25")));

        service.snapshotVersion(table, "添加第1行");
        table.setRows(List.of(List.of("张三", "25"), List.of("李四", "30")));

        ExcelTableVersion restored = service.restoreLatestVersion(table);
        assertNotNull(restored);
        assertEquals(List.of(List.of("张三", "25")), table.getRows());
        // 恢复后版本仍在，由调用方在导出并保存成功后 consumeVersion 消费
        assertEquals(1, service.versionCount(table));
        service.consumeVersion(restored);
        assertEquals(0, service.versionCount(table));
    }

    @Test
    void restoreLatestVersionFailsWithoutVersions() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");

        assertNull(service.restoreLatestVersion(table));
    }

    @Test
    void restoreSkipsJustPushedRollbackSnapshot() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三")));

        // 变更前快照（记录初始状态），随后表格被修改
        service.snapshotVersion(table, "添加第1行");
        table.setRows(List.of(List.of("张三"), List.of("李四")));
        // 回滚流程：先快照当前状态（「回滚操作」），再恢复最新版本
        service.snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);

        ExcelTableVersion restored = service.restoreLatestVersion(table);
        assertNotNull(restored);
        assertEquals(List.of(List.of("张三")), table.getRows());
        // 恢复的变更前快照在消费前仍保留；回滚快照保留用于再次撤销
        assertEquals(2, service.versionCount(table));
        service.consumeVersion(restored);
        assertEquals(1, service.versionCount(table));
        assertEquals(ExcelService.ROLLBACK_DESCRIPTION,
            service.recentVersions(table, 5).get(0).getDescription());
    }

    @Test
    void rollbackRoundTripAlternatesStates() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");

        // 两张变更前快照 + 三个递增状态
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三")));
        service.snapshotVersion(table, "初始");
        table.setRows(List.of(List.of("张三"), List.of("李四")));
        service.snapshotVersion(table, "添加第2行");
        table.setRows(List.of(List.of("张三"), List.of("李四"), List.of("王五")));

        // 第一次回滚：回到两行
        service.snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        ExcelTableVersion restored = service.restoreLatestVersion(table);
        service.consumeVersion(restored);
        assertEquals(2, table.getRows().size());

        // 第二次回滚：撤销回滚，回到三行
        service.snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        restored = service.restoreLatestVersion(table);
        service.consumeVersion(restored);
        assertEquals(3, table.getRows().size());

        // 第三次回滚：再次回到两行，交替稳定
        service.snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        restored = service.restoreLatestVersion(table);
        service.consumeVersion(restored);
        assertEquals(2, table.getRows().size());
    }

    @Test
    void versionsPrunedBeyondLimit() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三")));

        for (int i = 0; i < 25; i++) {
            service.snapshotVersion(table, "操作" + i);
        }

        // 超出上限的旧版本被清理，只保留最近 20 条
        assertEquals(20, service.versionCount(table));
        List<ExcelTableVersion> recent = service.recentVersions(table, 100);
        assertEquals(20, recent.size());
        assertEquals("操作24", recent.get(0).getDescription());
        assertEquals("操作5", recent.get(19).getDescription());
    }

    // ============================
    // 版本对比（diffVersions）
    // ============================
    @Test
    void diffVersionsWithoutVersionsReturnsHint() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三")));

        assertTrue(service.diffVersions(table).contains("还没有可对比的版本"));
    }

    @Test
    void diffVersionsReportsAddedRows() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三")));
        service.snapshotVersion(table, "初始");

        // 当前表新增一行：集合差 → 新增 1 行，其余 0
        table.setRows(List.of(List.of("张三"), List.of("李四")));
        String diff = service.diffVersions(table);

        assertTrue(diff.contains("表头无变化"));
        assertTrue(diff.contains("新增 1 行"));
        assertTrue(diff.contains("删除 0 行"));
        assertTrue(diff.contains("修改 0 行"));
    }

    @Test
    void diffVersionsReportsRemovedRows() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名"));
        table.setRows(List.of(List.of("张三"), List.of("李四")));
        service.snapshotVersion(table, "初始");

        // 当前表删除一行：集合差 → 删除 1 行
        table.setRows(List.of(List.of("张三")));
        String diff = service.diffVersions(table);

        assertTrue(diff.contains("新增 0 行"));
        assertTrue(diff.contains("删除 1 行"));
    }

    @Test
    void diffVersionsReportsModifiedRowsAndHeaderChange() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(List.of(List.of("张三", "25"), List.of("李四", "30")));
        service.snapshotVersion(table, "初始");

        // 当前表同行号内容不同（第二行被修改），表头也变化 → 修改 1 行 + 表头有变化
        table.setHeaders(List.of("姓名", "年龄(周岁)"));
        table.setRows(List.of(List.of("张三", "25"), List.of("李四", "31")));
        String diff = service.diffVersions(table);

        assertTrue(diff.contains("表头有变化"));
        assertTrue(diff.contains("新增 1 行"));
        assertTrue(diff.contains("删除 1 行"));
        assertTrue(diff.contains("修改 1 行"));
    }

    @Test
    void diffVersionsWithNullTableReturnsHint() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);

        assertTrue(service.diffVersions(null).contains("还没有可对比的版本"));
    }

    // ============================
    // 7. 公式单元格（导出时识别；非法公式保持文本；求值错误取消导出）
    // ============================
    @Test
    void exportsSafeFormulaAsFormulaCell() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("单价", "数量", "小计"));
        table.setRows(List.of(
            List.of("10", "3", "=A2*B2"),
            List.of("20", "4", "=1+1")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Cell multiply = sheet.getRow(1).getCell(2);
            assertEquals(CellType.FORMULA, multiply.getCellType());
            assertEquals("A2*B2", multiply.getCellFormula());
            // 纯数字公式同样走公式分支（优先级高于数字推断）
            Cell pureNumeric = sheet.getRow(2).getCell(2);
            assertEquals(CellType.FORMULA, pureNumeric.getCellType());
            assertEquals("1+1", pureNumeric.getCellFormula());
        }
    }

    @Test
    void exportsSumRangeFormula() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("数值", "合计"));
        table.setRows(List.of(
            List.of("1", "=SUM(A2:A4)"),
            List.of("2", ""),
            List.of("3", "")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Cell cell = workbook.getSheetAt(0).getRow(1).getCell(1);
            assertEquals(CellType.FORMULA, cell.getCellType());
            assertEquals("SUM(A2:A4)", cell.getCellFormula());
        }
    }

    @Test
    void exportsWhitelistedFunctionFormulas() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("结果", "结果2"));
        table.setRows(List.of(
            List.of("=CONCATENATE(\"a\",\"b\")", "=IF(1,2,3)"),
            List.of("1", "2")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.FORMULA, row.getCell(0).getCellType());
            assertEquals("CONCATENATE(\"a\",\"b\")", row.getCell(0).getCellFormula());
            assertEquals(CellType.FORMULA, row.getCell(1).getCellType());
            assertEquals("IF(1,2,3)", row.getCell(1).getCellFormula());
        }
    }

    @Test
    void invalidFormulasStayAsText() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("非法公式1", "非法公式2", "非法公式3", "非法公式4"));
        table.setRows(List.of(List.of(
            "=hello",                          // 字母序列后无左括号
            "=HYPERLINK(\"http://x\",\"y\")",  // 非白名单函数
            "=Sheet1!A1",                      // 含 !（外部引用）
            "=SUM(A1)+√2")));                  // 含非 ASCII 字符
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            for (int c = 0; c < 4; c++) {
                assertEquals(CellType.STRING, row.getCell(c).getCellType());
            }
            assertEquals("=hello", row.getCell(0).getStringCellValue());
            assertEquals("=HYPERLINK(\"http://x\",\"y\")",
                row.getCell(1).getStringCellValue());
            assertEquals("=Sheet1!A1", row.getCell(2).getStringCellValue());
            assertEquals("=SUM(A1)+√2", row.getCell(3).getStringCellValue());
        }
    }

    @Test
    void poiOpaqueFormulasFallBackToText() throws Exception {
        // token 合法但 POI 无法解析的公式（相邻引用 A1B2、裸函数式引用 A1(）按文本写入，不中断导出
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("值1", "值2"));
        table.setRows(List.of(List.of("=A1B2", "=A1(")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, row.getCell(0).getCellType());
            assertEquals("=A1B2", row.getCell(0).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(1).getCellType());
            assertEquals("=A1(", row.getCell(1).getStringCellValue());
        }
    }

    @Test
    void formulaErrorCancelsExportWithClearMessage() {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("数值"));
        table.setRows(List.of(List.of("=1/0")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.toXlsx(table));
        assertTrue(error.getMessage().contains("#DIV/0!"));
        assertTrue(error.getMessage().contains("A2"));     // 出错单元格地址
        assertTrue(error.getMessage().contains("取消导出"));
    }

    @Test
    void formulaErrorMessageSuggestsRangeFunctionForm() {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("数值"));
        table.setRows(List.of(List.of("=1/0")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.toXlsx(table));
        // 错误码为 #DIV/0! 时同样给出区域写法的正确示例
        assertTrue(error.getMessage().contains("=SUM(A1:B2)"));
        assertTrue(error.getMessage().contains("请写成"));
    }

    @Test
    void formulaReferencingEmptyCellsIsNotAnError() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("甲", "乙", "合计"));
        table.setRows(List.of(List.of("", "", "=A2+B2")));
        // 空单元格引用按 0 参与计算，不应触发取消导出
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(CellType.FORMULA,
                workbook.getSheetAt(0).getRow(1).getCell(2).getCellType());
        }
    }

    @Test
    void forceFormulaRecalculationIsSet() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "公式表");
        table.setHeaders(List.of("数值"));
        table.setRows(List.of(List.of("=1+1")));
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertTrue(workbook.getForceFormulaRecalculation());
        }
    }

    // ============================
    // 8. 表格式化导出（标题行/冻结/筛选，每次导出自动应用）
    // ============================
    @Test
    void exportsTitleRowFreezeAndFilter() throws Exception {
        ExcelTable table = sampleTable();
        table.setTitleRow("销售报表");
        table.setFreezeHeader(true);
        table.setAutoFilter(true);
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
            // 标题行：第 0 行合并跨全部列，文本为标题
            assertEquals("销售报表", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(1, sheet.getMergedRegions().size());
            assertEquals(new CellRangeAddress(0, 0, 0, 2), sheet.getMergedRegions().get(0));
            // 表头行从第 1 行开始，数据从第 2 行开始
            assertEquals("姓名", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("张三", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(5, sheet.getLastRowNum() + 1); // 标题 + 表头 + 3 行数据
            // 冻结窗格：冻结标题 + 表头两行（行冻结看水平分割位置）
            PaneInformation pane = sheet.getPaneInformation();
            assertNotNull(pane);
            assertTrue(pane.isFreezePane());
            assertEquals(2, pane.getHorizontalSplitPosition());
            // 自动筛选：表头 + 数据范围
            assertTrue(sheet.getCTWorksheet().isSetAutoFilter());
        }
    }

    /** titleRow 导致表头偏移后，数据行数/位置仍然正确（表头在第 1 行、数据从第 2 行起）。 */
    @Test
    void titleRowOffsetsHeaderAndDataRows() throws Exception {
        ExcelTable table = sampleTable();
        table.setTitleRow("测试表标题");
        byte[] bytes = service.toXlsx(table);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
            assertEquals("姓名", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("张三", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("王五", sheet.getRow(4).getCell(0).getStringCellValue());
            // 无冻结/筛选设置时不应产生窗格与筛选（回归：未格式化导出与现状一致）
            assertNull(sheet.getPaneInformation());
            assertFalse(sheet.getCTWorksheet().isSetAutoFilter());
        }
    }

    /** 只冻结不筛选、只筛选不冻结时各自生效。 */
    @Test
    void exportsFreezeAndFilterIndependently() throws Exception {
        ExcelTable table = sampleTable();
        table.setFreezeHeader(true);
        byte[] freezeOnly = service.toXlsx(table);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(freezeOnly))) {
            XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
            PaneInformation pane = sheet.getPaneInformation();
            assertNotNull(pane);
            assertTrue(pane.isFreezePane());
            assertEquals(1, pane.getHorizontalSplitPosition()); // 只冻结表头一行
            assertFalse(sheet.getCTWorksheet().isSetAutoFilter());
        }
        ExcelTable filtered = sampleTable();
        filtered.setAutoFilter(true);
        byte[] filterOnly = service.toXlsx(filtered);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(filterOnly))) {
            XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
            assertNull(sheet.getPaneInformation());
            assertTrue(sheet.getCTWorksheet().isSetAutoFilter());
        }
    }

    // ============================
    // 9. 图表导出（新增「图表」工作表）
    // ============================
    @Test
    void exportsChartSheetWithBarChart() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "销售表");
        table.setHeaders(List.of("产品", "销售额"));
        table.setRows(List.of(
            List.of("A", "100"), List.of("B", "200"), List.of("C", "150")));
        byte[] bytes = service.toXlsxWithChart(table, "BAR", "产品", "销售额");
        assertTrue(bytes.length > 0);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertNotNull(workbook.getSheet("图表"));
            XSSFSheet chartSheet = (XSSFSheet) workbook.getSheet("图表");
            // 分类/数值解析后写入图表工作表（首行为表头，数值列非数值按 0）
            assertEquals("产品", chartSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("销售额", chartSheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("A", chartSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(100.0, chartSheet.getRow(1).getCell(1).getNumericCellValue(), 0.0001);
            assertEquals("C", chartSheet.getRow(3).getCell(0).getStringCellValue());
            // 图表对象存在（chart 关联部件挂在绘图对象下）
            XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
            assertTrue(drawing.getRelations().stream()
                .anyMatch(part -> part instanceof XSSFChart));
            // 坐标轴位置规范：数值轴在左、分类轴在底（两轴叠底会导致图表不显示）
            XSSFChart chart = (XSSFChart) drawing.getRelations().stream()
                .filter(part -> part instanceof XSSFChart).findFirst().orElseThrow();
            CTValAx valAx = chart.getCTChart().getPlotArea().getValAxArray(0);
            assertEquals(STAxPos.L, valAx.getAxPos().getVal());
        }
    }

    /** 多图表导出：每张图各占一个工作表（图表、图表2）。 */
    @Test
    void toXlsxWithChartsCreatesOneSheetPerChart() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "销售表");
        table.setHeaders(List.of("产品", "销售额"));
        table.setRows(List.of(
            List.of("A", "100"), List.of("B", "200"), List.of("C", "150")));
        byte[] bytes = service.toXlsxWithCharts(table, List.of(
            new ExcelService.ChartSpec("BAR", "产品", "销售额"),
            new ExcelService.ChartSpec("PIE", "产品", "销售额")));
        assertTrue(bytes.length > 0);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertNotNull(workbook.getSheet("图表"));
            assertNotNull(workbook.getSheet("图表2"));
        }
    }

    @Test
    void exportsLineAndPieCharts() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "销售表");
        table.setHeaders(List.of("产品", "销售额"));
        table.setRows(List.of(
            List.of("A", "100"), List.of("B", "200"),
            List.of("C", "150"), List.of("D", "180")));
        for (String chartType : List.of("LINE", "PIE")) {
            byte[] bytes = service.toXlsxWithChart(table, chartType, "产品", "销售额");
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                XSSFSheet chartSheet = (XSSFSheet) workbook.getSheet("图表");
                XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
                assertTrue(drawing.getRelations().stream()
                    .anyMatch(part -> part instanceof XSSFChart));
            }
        }
    }

    /** 数值列非数值按 0 参与图表；空分类行跳过。 */
    @Test
    void chartNonNumericValuesCountAsZeroAndBlankCategoriesSkipped() throws Exception {
        ExcelTable table = new ExcelTable("user-1", "销售表");
        table.setHeaders(List.of("产品", "销售额"));
        table.setRows(List.of(
            List.of("A", "abc"), List.of("", "999"), List.of("C", "150")));
        byte[] bytes = service.toXlsxWithChart(table, "BAR", "产品", "销售额");

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            XSSFSheet chartSheet = (XSSFSheet) workbook.getSheet("图表");
            // 第一行：A/abc → 数值按 0；空分类行被跳过
            assertEquals("A", chartSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(0.0, chartSheet.getRow(1).getCell(1).getNumericCellValue(), 0.0001);
            assertEquals("C", chartSheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(150.0, chartSheet.getRow(2).getCell(1).getNumericCellValue(), 0.0001);
        }
    }

    /** 图表数据较少（不足 2 条）时失败，提示确认列。 */
    @Test
    void chartWithInsufficientDataFails() {
        ExcelTable table = new ExcelTable("user-1", "销售表");
        table.setHeaders(List.of("产品", "销售额"));
        table.setRows(List.of(List.of("A", "100")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.toXlsxWithChart(table, "BAR", "产品", "销售额"));
        assertTrue(error.getMessage().contains("图表数据不足"));

        // 空表格同样失败
        ExcelTable empty = new ExcelTable("user-1", "空表");
        empty.setHeaders(List.of("产品", "销售额"));
        assertThrows(IllegalArgumentException.class,
            () -> service.toXlsxWithChart(empty, "BAR", "产品", "销售额"));
    }

    /** 图表导出与普通导出共用数据工作表构建：无图表导出时数据工作表行为不变（回归）。 */
    @Test
    void toXlsxWithChartKeepsDataSheetIntact() throws Exception {
        ExcelTable table = sampleTable();
        byte[] withChart = service.toXlsxWithChart(table, "BAR", "姓名", "年龄");
        byte[] plain = service.toXlsx(table);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(withChart))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("测试表", sheet.getSheetName());
            assertEquals("姓名", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(4, sheet.getLastRowNum() + 1);
            assertNotNull(workbook.getSheet("图表"));
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(plain))) {
            assertNull(workbook.getSheet("图表"));
            assertEquals(1, workbook.getNumberOfSheets());
        }
    }

    // ============================
    // 10. 汇总页导出（新增「汇总」工作表）
    // ============================
    @Test
    void exportsDashboardSheetWithColumnSummary() throws Exception {
        ExcelTable table = sampleTable(); // 姓名/年龄/工资 × 3 行
        byte[] bytes = service.toXlsxWithDashboard(table);
        assertTrue(bytes.length > 0);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertNotNull(workbook.getSheet("汇总"));
            Sheet summary = workbook.getSheet("汇总");
            // 表标题（工作表名）+ 列数/行数
            assertTrue(summary.getRow(0).getCell(0).getStringCellValue().contains("测试表"));
            assertTrue(summary.getRow(1).getCell(0).getStringCellValue().contains("3 列"));
            assertTrue(summary.getRow(1).getCell(0).getStringCellValue().contains("3 行"));
            // 表头：列名 | 合计 | 平均
            assertEquals("列名", summary.getRow(2).getCell(0).getStringCellValue());
            assertEquals("合计", summary.getRow(2).getCell(1).getStringCellValue());
            assertEquals("平均", summary.getRow(2).getCell(2).getStringCellValue());
            // 非数值列（姓名）标「-」
            assertEquals("-", summary.getRow(3).getCell(1).getStringCellValue());
            assertEquals("-", summary.getRow(3).getCell(2).getStringCellValue());
            // 数值列合计与平均：年龄 25+30+28=83、平均 27.67；工资 27500
            assertEquals(83.0, summary.getRow(4).getCell(1).getNumericCellValue(), 0.0001);
            assertEquals(27.67, summary.getRow(4).getCell(2).getNumericCellValue(), 0.001);
            assertEquals(27500.0, summary.getRow(5).getCell(1).getNumericCellValue(), 0.0001);
            // 简单说明文本
            assertTrue(summary.getRow(6).getCell(0).getStringCellValue().contains("自动生成"));
        }
    }

    /** 表格标题恰好为「图表」「汇总」时新增工作表加后缀，避免同名冲突（其余情况仍用固定名）。 */
    @Test
    void chartAndDashboardSheetNamesAvoidTitleCollision() throws Exception {
        ExcelTable chartTable = new ExcelTable("user-1", "图表");
        chartTable.setHeaders(List.of("产品", "销售额"));
        chartTable.setRows(List.of(
            List.of("A", "100"), List.of("B", "200"), List.of("C", "150")));
        byte[] chartBytes = service.toXlsxWithChart(chartTable, "BAR", "产品", "销售额");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(chartBytes))) {
            assertNotNull(workbook.getSheet("图表2"));
        }
        ExcelTable dashboardTable = new ExcelTable("user-1", "汇总");
        dashboardTable.setHeaders(List.of("产品", "销售额"));
        dashboardTable.setRows(List.of(List.of("A", "100")));
        byte[] dashboardBytes = service.toXlsxWithDashboard(dashboardTable);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(dashboardBytes))) {
            assertNotNull(workbook.getSheet("汇总2"));
        }
    }

    /** 汇总页导出不修改数据工作表（回归：既有导出行为不变）。 */
    @Test
    void toXlsxWithDashboardKeepsDataSheetIntact() throws Exception {
        ExcelTable table = sampleTable();
        byte[] bytes = service.toXlsxWithDashboard(table);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("测试表", sheet.getSheetName());
            assertEquals("姓名", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(4, sheet.getLastRowNum() + 1);
        }
    }

    /** 内存版版本仓库：模拟 Mongo 的按创建时间倒序查询、保存/删除/计数。 */
    private static final class FakeVersionRepository
        implements ExcelTableVersionRepository {
        private final Map<String, ExcelTableVersion> store = new LinkedHashMap<>();
        private long seq;
        private long timeSeq;

        @Override
        public <S extends ExcelTableVersion> S save(S version) {
            if (version.getId() == null) {
                version.setId("v" + (++seq));
            }
            // 模拟 MongoDB 单调递增的写入时间，避免同一毫秒内的快照并列导致倒序不确定
            version.setCreatedAt(Instant.ofEpochMilli(++timeSeq));
            store.put(version.getId(), version);
            return version;
        }

        @Override
        public <S extends ExcelTableVersion> S insert(S entity) {
            return save(entity);
        }

        @Override
        public <S extends ExcelTableVersion> List<S> insert(Iterable<S> entities) {
            return saveAll(entities);
        }

        @Override
        public <S extends ExcelTableVersion> List<S> saveAll(Iterable<S> entities) {
            List<S> saved = new ArrayList<>();
            for (S entity : entities) {
                saved.add(save(entity));
            }
            return saved;
        }

        @Override
        public Optional<ExcelTableVersion> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsById(String id) {
            return store.containsKey(id);
        }

        @Override
        public List<ExcelTableVersion> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<ExcelTableVersion> findAllById(Iterable<String> ids) {
            List<ExcelTableVersion> result = new ArrayList<>();
            for (String id : ids) {
                ExcelTableVersion version = store.get(id);
                if (version != null) result.add(version);
            }
            return result;
        }

        @Override
        public long count() {
            return store.size();
        }

        @Override
        public void deleteById(String id) {
            store.remove(id);
        }

        @Override
        public void delete(ExcelTableVersion version) {
            store.remove(version.getId());
        }

        @Override
        public void deleteAllById(Iterable<? extends String> ids) {
            for (String id : ids) {
                store.remove(id);
            }
        }

        @Override
        public void deleteAll(Iterable<? extends ExcelTableVersion> entities) {
            for (ExcelTableVersion version : entities) {
                store.remove(version.getId());
            }
        }

        @Override
        public void deleteAll() {
            store.clear();
        }

        @Override
        public List<ExcelTableVersion> findAll(Sort sort) {
            return new ArrayList<>(store.values());
        }

        @Override
        public Page<ExcelTableVersion> findAll(Pageable pageable) {
            return new PageImpl<>(new ArrayList<>(store.values()));
        }

        // QueryByExampleExecutor 的方法在本测试中不使用，给出空实现即可
        @Override
        public <S extends ExcelTableVersion> Optional<S> findOne(Example<S> example) {
            return Optional.empty();
        }

        @Override
        public <S extends ExcelTableVersion> List<S> findAll(Example<S> example) {
            return List.of();
        }

        @Override
        public <S extends ExcelTableVersion> List<S> findAll(Example<S> example, Sort sort) {
            return List.of();
        }

        @Override
        public <S extends ExcelTableVersion> Page<S> findAll(Example<S> example,
                                                             Pageable pageable) {
            return Page.empty();
        }

        @Override
        public <S extends ExcelTableVersion> long count(Example<S> example) {
            return 0;
        }

        @Override
        public <S extends ExcelTableVersion> boolean exists(Example<S> example) {
            return false;
        }

        @Override
        public <S extends ExcelTableVersion, R> R findBy(Example<S> example,
            Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            return null;
        }

        @Override
        public List<ExcelTableVersion> findByTableIdOrderByCreatedAtDesc(String tableId) {
            return store.values().stream()
                .filter(version -> tableId.equals(version.getTableId()))
                .sorted(Comparator.comparing(ExcelTableVersion::getCreatedAt).reversed())
                .toList();
        }

        @Override
        public long countByTableId(String tableId) {
            return store.values().stream()
                .filter(version -> tableId.equals(version.getTableId()))
                .count();
        }
    }
}
