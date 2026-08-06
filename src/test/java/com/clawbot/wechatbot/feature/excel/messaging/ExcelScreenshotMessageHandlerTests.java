package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.service.VisionService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

/** 表格截图处理器测试。 */
class ExcelScreenshotMessageHandlerTests {

    private final ExcelService excelService = mock(ExcelService.class);
    private final VisionService visionService = mock(VisionService.class);
    private final ExcelScreenshotMessageHandler handler =
        new ExcelScreenshotMessageHandler(excelService, visionService);

    @Test
    void canHandleImageWithTableKeyword() {
        assertTrue(handler.canHandle(imageMessage("把这张图做成表格")));
        assertTrue(handler.canHandle(imageMessage("生成表格")));
        assertTrue(handler.canHandle(imageMessage("转成 Excel")));
        assertTrue(handler.canHandle(imageMessage("转成 xlsx")));
    }

    @Test
    void cannotHandleImageWithoutTableKeyword() {
        assertFalse(handler.canHandle(imageMessage("这张图里有什么")));
    }

    @Test
    void cannotHandleTextOnlyMessage() {
        assertFalse(handler.canHandle(textOnlyMessage("生成表格")));
    }

    /** 表格截图属于强领域意图：声明绕过统一任务规划，避免被 LLM 规划改写/劫持。 */
    @Test
    void bypassesPlanningIsTrue() {
        assertTrue(handler.bypassesPlanning());
    }

    @Test
    void handleSkipsWhenVisionNotConfigured() throws Exception {
        when(visionService.isConfigured()).thenReturn(false);
        ILinkClient client = mock(ILinkClient.class);

        handler.handle(client, imageMessage("做成表格"));

        assertLastReplyContains(client, "DASHSCOPE_API_KEY");
        verify(client, never()).downloadImageFromMessageItem(any());
        verify(excelService, never()).save(any());
    }

    @Test
    void handleRecognizesTableAndSaves() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "截图表格");
        when(excelService.createWorkbook(eq("wechat-user"), eq("截图表格")))
            .thenReturn(table);
        when(visionService.isConfigured()).thenReturn(true);
        when(visionService.understandImage(any(), anyString()))
            .thenReturn("姓名,年龄\n张三,25\n李四,30");
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadImageFromMessageItem(any(MessageItem.class)))
            .thenReturn(new byte[]{1, 2, 3});

        handler.handle(client, imageMessage("做成表格"));

        // 解析成功：表头与数据行写入表格
        assertEquals(List.of("姓名", "年龄"), table.getHeaders());
        assertEquals(2, table.getRows().size());
        verify(excelService).createWorkbook("wechat-user", "截图表格");
        verify(excelService).save(table);
        assertLastReplyContains(client, "已从截图识别出 2 行数据（2列）");
        assertLastReplyContains(client, "已新建「截图表格」并切换为当前表格");
        // 新表没有原数据，不产生替换快照
        verify(excelService, never()).snapshotVersion(any(), anyString());
        // 附 xlsx 附件
        verify(client).sendFile(anyString(), any(byte[].class), anyString(), anyString());
    }

    @Test
    void handleFailsGracefullyWhenVisionReturnsUnparseableText() throws Exception {
        when(visionService.isConfigured()).thenReturn(true);
        when(visionService.understandImage(any(), anyString()))
            .thenReturn("这张图里没有表格");
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadImageFromMessageItem(any(MessageItem.class)))
            .thenReturn(new byte[]{1, 2, 3});

        handler.handle(client, imageMessage("做成表格"));

        assertLastReplyContains(client, "没能从图片里识别出表格");
        verify(excelService, never()).save(any());
    }

    /** 即使已有活动表，截图转表格也总是新建一张「截图表格」，绝不替换现有数据。 */
    @Test
    void handleAlwaysCreatesNewWorkbookInsteadOfReplacing() throws Exception {
        ExcelTable fresh = new ExcelTable("wechat-user", "截图表格");
        when(excelService.createWorkbook(eq("wechat-user"), eq("截图表格")))
            .thenReturn(fresh);
        when(visionService.isConfigured()).thenReturn(true);
        when(visionService.understandImage(any(), anyString()))
            .thenReturn("姓名,年龄\n张三,25");
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadImageFromMessageItem(any(MessageItem.class)))
            .thenReturn(new byte[]{1, 2, 3});

        handler.handle(client, imageMessage("做成表格"));

        // 数据落入新建的「截图表格」，原活动表不受影响
        assertEquals(List.of("姓名", "年龄"), fresh.getHeaders());
        assertEquals(1, fresh.getRows().size());
        verify(excelService).createWorkbook("wechat-user", "截图表格");
        verify(excelService, never()).snapshotVersion(any(), anyString());
        assertLastReplyContains(client, "已新建「截图表格」并切换为当前表格");
    }

    /** 截图转表格始终走新建路径（多工作簿语义）。 */
    @Test
    void handleCreatesNewWorkbookWhenNoActiveTable() throws Exception {
        ExcelTable table = new ExcelTable("wechat-user", "截图表格");
        when(excelService.createWorkbook(eq("wechat-user"), eq("截图表格"))).thenReturn(table);
        when(visionService.isConfigured()).thenReturn(true);
        when(visionService.understandImage(any(), anyString()))
            .thenReturn("姓名,年龄\n张三,25");
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadImageFromMessageItem(any(MessageItem.class)))
            .thenReturn(new byte[]{1, 2, 3});

        handler.handle(client, imageMessage("做成表格"));

        // 新建的表成为导入目标：数据落表，不产生替换快照
        assertEquals(List.of("姓名", "年龄"), table.getHeaders());
        assertEquals(1, table.getRows().size());
        verify(excelService).createWorkbook("wechat-user", "截图表格");
        verify(excelService, never()).snapshotVersion(any(), anyString());
        verify(excelService).save(table);
        assertLastReplyContains(client, "已新建「截图表格」并切换为当前表格");
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

    /** 带一张图片和一段文本的消息。 */
    private WeixinMessage imageMessage(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("wechat-user");
        MessageItem textItem = MessageItem.text(text);
        MessageItem imageItem = new MessageItem();
        imageItem.setImage_item(new ImageItem());
        message.setItem_list(List.of(textItem, imageItem));
        return message;
    }

    /** 只有文本没有图片的消息。 */
    private WeixinMessage textOnlyMessage(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("wechat-user");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }
}
