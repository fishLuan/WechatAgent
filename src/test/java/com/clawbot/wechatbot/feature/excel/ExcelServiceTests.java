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

        assertTrue(service.restoreLatestVersion(table));
        assertEquals(List.of(List.of("张三", "25")), table.getRows());
        // 恢复后该版本被消费，避免下一次回滚到同一状态
        assertEquals(0, service.versionCount(table));
    }

    @Test
    void restoreLatestVersionFailsWithoutVersions() {
        FakeVersionRepository fake = new FakeVersionRepository();
        ExcelService service = versionedService(fake);
        ExcelTable table = new ExcelTable("user-1", "表");
        table.setId("t1");

        assertFalse(service.restoreLatestVersion(table));
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

        assertTrue(service.restoreLatestVersion(table));
        assertEquals(List.of(List.of("张三")), table.getRows());
        // 回滚快照仍保留，用于再次撤销回滚
        assertEquals(1, service.versionCount(table));
        assertEquals(ExcelService.ROLLBACK_DESCRIPTION,
            service.recentVersions(table, 5).get(0).getDescription());
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

