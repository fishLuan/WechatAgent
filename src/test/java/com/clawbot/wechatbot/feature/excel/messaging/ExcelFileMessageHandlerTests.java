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

    @Test
    void handleImportsWorkbookIntoUserTable() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "旧表");
        table.setHeaders(List.of("旧列"));
        table.setRows(List.of(List.of("旧数据")));
        when(excelService.loadOrCreate(anyString(), anyString())).thenReturn(table);

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25"), List.of("李四", "30")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        assertEquals(List.of("姓名", "年龄"), table.getHeaders());
        assertEquals(2, table.getRows().size());
        assertEquals(List.of("张三", "25"), table.getRows().get(0));
        verify(excelService).save(table);
        assertLastReplyContains(client, "已导入 2 行数据（2列）");
        assertLastReplyContains(client, "替换");
    }

    @Test
    void handleReportsImportedRowCountOnFreshTable() throws Exception {
        when(excelService.loadOrCreate(anyString(), anyString()))
            .thenReturn(new ExcelTable("wechat-user", "新表"));

        ILinkClient client = mock(ILinkClient.class);
        byte[] bytes = xlsxBytes(List.of("姓名", "年龄"),
            List.of(List.of("张三", "25")));
        when(client.downloadFileFromMessageItem(any(MessageItem.class))).thenReturn(bytes);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("员工表.xlsx", String.valueOf(bytes.length)));

        assertLastReplyContains(client, "已导入 1 行数据（2列）");
        assertLastReplyNotContains(client, "替换");
        verify(excelService).save(any());
    }

    @Test
    void handleRejectsOversizedFile() throws Exception {
        when(excelService.loadOrCreate(anyString(), anyString()))
            .thenReturn(new ExcelTable("wechat-user", "旧表"));
        ILinkClient client = mock(ILinkClient.class);

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("大文件.xlsx", String.valueOf(11L * 1024 * 1024)));

        assertLastReplyContains(client, "超过 10MB");
        verify(client, never()).downloadFileFromMessageItem(any());
        verify(excelService, never()).save(any());
    }

    @Test
    void handleRejectsFakeXlsxFile() throws Exception {
        when(excelService.loadOrCreate(anyString(), anyString()))
            .thenReturn(new ExcelTable("wechat-user", "旧表"));
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadFileFromMessageItem(any(MessageItem.class)))
            .thenReturn("这不是一个zip文件".getBytes());

        ExcelFileMessageHandler handler = new ExcelFileMessageHandler(excelService);
        handler.handle(client, xlsxMessage("伪装.xlsx", "30"));

        assertLastReplyContains(client, "不是有效的 xlsx");
        verify(excelService, never()).save(any());
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
