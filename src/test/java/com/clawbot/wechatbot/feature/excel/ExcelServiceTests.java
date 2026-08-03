package com.clawbot.wechatbot.feature.excel;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelServiceTests {

    private final ExcelService service = new ExcelService(null);

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
}
