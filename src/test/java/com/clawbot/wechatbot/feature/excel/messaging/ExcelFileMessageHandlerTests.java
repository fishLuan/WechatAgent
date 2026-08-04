package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** xlsx 上传导入处理器测试。 */
class ExcelFileMessageHandlerTests {

    private final ExcelService excelService = mock(ExcelService.class);

    @Test
    void canHandleXlsxFileMessage() {
        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        assertTrue(handler.canHandle(xlsxMessage("员工表.xlsx", "1024")));
        assertTrue(handler.canHandle(xlsxMessage("DATA.XLSX", "1024")));
    }

    @Test
    void cannotHandleNonXlsxFileMessage() {
        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        assertFalse(handler.canHandle(xlsxMessage("报告.pdf", "1024")));
        assertFalse(handler.canHandle(xlsxMessage("文档.docx", "1024")));
        assertFalse(handler.canHandle(xlsxMessage("笔记.txt", "1024")));
    }

    /** xlsm/xls 也被本处理器接收（canHandle 命中），由 handle 给出明确拒绝引导。 */
    @Test
    void canHandleXlsmAndXlsFileMessage() {
        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        assertTrue(handler.canHandle(xlsxMessage("宏文件.xlsm", "1024")));
        assertTrue(handler.canHandle(xlsxMessage("DATA.XLSM", "1024")));
        assertTrue(handler.canHandle(xlsxMessage("旧数据.xls", "1024")));
        assertTrue(handler.canHandle(xlsxMessage("OLD.XLS", "1024")));
    }

    // ============================
    // xlsm / xls 明确拒绝（不下载、不解析）
    // ============================
    @Test
    void handleRejectsXlsmFileWithGuide() throws Exception {
        ILinkClient client = mock(ILinkClient.class);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("宏文件.xlsm", "1024"));

        assertLastReplyContains(client, "暂不支持含宏的 xlsm 文件");
        assertLastReplyContains(client, "另存为 .xlsx");
        // 拒绝时不下载、不落库
        verify(client, never()).downloadFileFromMessageItem(any());
        verify(excelService, never()).save(any());
    }

    @Test
    void handleRejectsXlsFileWithGuide() throws Exception {
        ILinkClient client = mock(ILinkClient.class);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("旧数据.xls", "1024"));

        assertLastReplyContains(client, "暂不支持旧版 .xls 格式");
        assertLastReplyContains(client, "另存为 .xlsx");
        verify(client, never()).downloadFileFromMessageItem(any());
        verify(excelService, never()).save(any());
    }

    // ============================
    // 工作表数量上限 / 行列数上限
    // ============================
    @Test
    void handleRejectsTooManySheets() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytesWithSheets(11);
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("多表.xlsx", String.valueOf(bytes.length)));

        assertLastReplyContains(client, "过多工作表");
        assertLastReplyContains(client, "超过 10 个");
        verify(excelService, never()).save(any());
        verify(excelService, never()).createWorkbook(anyString(), anyString());
    }

    @Test
    void handleRejectsImportOverRowLimit() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        List<List<String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i <= ExcelService.MAX_TABLE_ROWS; i++) {
            rows.add(List.of("张" + i, "25"));
        }
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"), rows);
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("大表.xlsx", String.valueOf(bytes.length)));

        assertLastReplyContains(client, "表格超出上限");
        assertLastReplyContains(client, "5000 行");
        verify(excelService, never()).save(any());
        verify(excelService, never()).createWorkbook(anyString(), anyString());
    }

    @Test
    void handleRejectsImportOverColumnLimit() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        List<String> headers = new java.util.ArrayList<>();
        for (int i = 0; i <= ExcelService.MAX_TABLE_COLUMNS; i++) {
            headers.add("列" + i);
        }
        byte[] bytes = xlsxBytes(headers, List.of(List.of("张")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("宽表.xlsx", String.valueOf(bytes.length)));

        assertLastReplyContains(client, "表格超出上限");
        assertLastReplyContains(client, "100 列");
        verify(excelService, never()).save(any());
        verify(excelService, never()).createWorkbook(anyString(), anyString());
    }

    @Test
    void handleImportsWorkbookIntoUserTable() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "旧表");
        table.setHeaders(List.of("旧列"));
        table.setRows(List.of(List.of("旧数据")));
        when(excelService.getActiveWorkbook(anyString())).thenReturn(table);

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25"), List.of("李四", "30")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        assertEquals(List.of("姓名", "年龄"), table.getHeaders());
        assertEquals(2, table.getRows().size());
        assertEquals(List.of("张三", "25"), table.getRows().get(0));
        // 替换已有表格前先快照，保留原数据以便回滚
        verify(excelService).snapshotVersion(table, "导入替换");
        verify(excelService).save(table);
        assertLastReplyContains(client, "已导入 2 行数据（2列）");
        assertLastReplyContains(client, "替换");
    }

    @Test
    void handleReportsImportedRowCountOnFreshTable() throws Exception {
        when(excelService.createWorkbook(anyString(), anyString()))
            .thenReturn(new ExcelTable("wechat-user", "新表"));

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        assertLastReplyContains(client, "已导入 1 行数据（2列）");
        assertLastReplyNotContains(client, "替换");
        // 新表导入不产生快照（没有原数据可保留）
        verify(excelService, never()).snapshotVersion(any(), anyString());
        verify(excelService).save(any());
    }

    @Test
    void importReplyIncludesHeaderPreview() throws Exception {
        when(excelService.createWorkbook(anyString(), anyString()))
            .thenReturn(new ExcelTable("wechat-user", "新表"));

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄", "城市"),
            List.of(List.of("张三", "25", "北京")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        // 表头预览：全部表头用顿号连接，原「已导入…」文案保留
        assertLastReplyContains(client, "表头：姓名、年龄、城市");
        assertLastReplyContains(client, "已导入 1 行数据（3列）");
    }

    @Test
    void replaceImportReplyAlsoIncludesHeaderPreview() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "旧表");
        table.setHeaders(List.of("旧列"));
        table.setRows(List.of(List.of("旧数据")));
        when(excelService.getActiveWorkbook(anyString())).thenReturn(table);

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        assertLastReplyContains(client, "表头：姓名、年龄");
        assertLastReplyContains(client, "替换");
    }

    /** 端到端回归：POI 生成的真实 xlsx 字节走完下载→解析→落表→回复全链路，不 mock 解析结果。 */
    @Test
    void endToEndImportWithRealXlsxBytesLandsTableAndPreview() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "旧表");
        table.setHeaders(List.of("旧列"));
        table.setRows(List.of(List.of("旧数据")));
        when(excelService.getActiveWorkbook(anyString())).thenReturn(table);

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25"), List.of("李四", "30")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        // 真实字节解析出的表头与行数正确落表
        assertEquals(List.of("姓名", "年龄"), table.getHeaders());
        assertEquals(2, table.getRows().size());
        assertEquals(List.of("李四", "30"), table.getRows().get(1));
        // 回复同时包含导入统计与表头预览
        assertLastReplyContains(client, "已导入 2 行数据（2列）");
        assertLastReplyContains(client, "表头：姓名、年龄");
        assertLastReplyContains(client, "替换");
    }

    @Test
    void handleRejectsOversizedFile() throws Exception {
        ILinkClient client = mock(ILinkClient.class);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("大文件.xlsx", String.valueOf(11L * 1024 * 1024)));

        assertLastReplyContains(client, "超过 10MB");
        verify(client, never()).downloadFileFromMessageItem(any());
        verify(excelService, never()).save(any());
    }

    @Test
    void handleRejectsFakeXlsxFile() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadFileFromMessageItem(any(MessageItem.class)))
            .thenReturn("这不是一个zip文件".getBytes());

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("伪装.xlsx", "30"));

        assertLastReplyContains(client, "不是有效的 xlsx");
        verify(excelService, never()).save(any());
    }

    /** 没有活动表时：以文件名新建一张表并导入（多工作簿语义）。 */
    @Test
    void handleCreatesNewWorkbookWhenNoActiveTable() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "员工表");
        when(excelService.createWorkbook(eq("wechat-user"), eq("员工表"))).thenReturn(table);

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        // 新建的表成为导入目标：数据落表，且不产生替换快照
        assertEquals(List.of("姓名", "年龄"), table.getHeaders());
        assertEquals(1, table.getRows().size());
        verify(excelService).createWorkbook("wechat-user", "员工表");
        verify(excelService, never()).snapshotVersion(any(), anyString());
        verify(excelService).save(table);
        assertLastReplyContains(client, "已导入 1 行数据（2列）");
        assertLastReplyNotContains(client, "替换");
    }

    /** 断言至少有一条回复包含指定文本。 */
    private void assertLastReplyContains(ILinkClient client, String expected)
        throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).sendText(anyString(), captor.capture());
        List<String> replies = captor.getAllValues();
        assertTrue(replies.stream().anyMatch(text -> text.contains(expected)),
            "回复应包含「" + expected + "」，实际回复：" + replies);
    }

    /** 断言所有回复都不包含指定文本。 */
    private void assertLastReplyNotContains(ILinkClient client, String unexpected)
        throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).sendText(anyString(), captor.capture());
        List<String> replies = captor.getAllValues();
        assertTrue(replies.stream().noneMatch(text -> text.contains(unexpected)),
            "回复不应包含「" + unexpected + "」，实际回复：" + replies);
    }

    private WeixinMessage xlsxMessage(String fileName, String len) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("wechat-user");
        FileItem fileItem = new FileItem();
        fileItem.setFile_name(fileName);
        fileItem.setLen(len);
        MessageItem item = new MessageItem();
        item.setFile_item(fileItem);
        message.setItem_list(List.of(item));
        return message;
    }

    /** 用 POI 生成一份含指定数量工作表的 .xlsx 文件内容（每个工作表只有表头行）。 */
    private byte[] xlsxBytesWithSheets(int sheetCount) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.createSheet("Sheet" + (s + 1));
                sheet.createRow(0).createCell(0).setCellValue("姓名");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 用 POI 生成一份真实的 .xlsx 文件内容。 */
    private byte[] xlsxBytes(List<String> headers, List<List<String>> rows)
        throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    row.createCell(c).setCellValue(rows.get(r).get(c));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
