package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.skills.SkillManager;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Excel 文字指令处理器测试：纯文字且可解析的指令直接以原文绕过规划层调用技能。 */
class ExcelTextMessageHandlerTests {

    private final SkillManager skills = mock(SkillManager.class);
    private final ExcelTextMessageHandler handler = new ExcelTextMessageHandler(skills);

    private WeixinMessage textMessage(String text) {
        WeixinMessage message = new WeixinMessage();
        message.setFrom_user_id("user-1");
        message.setItem_list(List.of(MessageItem.text(text)));
        return message;
    }

    @Test
    void claimsRecognizableExcelTextCommands() {
        assertTrue(handler.canHandle(textMessage("生成表格：姓名,城市")));
        assertTrue(handler.canHandle(textMessage(
            "生成覆盖表格：单价,数量,金额\n10,5,=A2*B2\n20,3,=A3*B3")));
        assertTrue(handler.canHandle(textMessage(
            "覆盖\n单价,数量,金额\n10,5,=A2*B2\n20,3,=A3*B3")));
        assertTrue(handler.canHandle(textMessage("添加行：张三,北京")));
        assertTrue(handler.canHandle(textMessage("导出表格")));
        assertTrue(handler.canHandle(textMessage("查看所有工作簿")));
        assertTrue(handler.canHandle(textMessage("按数量降序")));
    }

    @Test
    void doesNotClaimChatQuestionsOrBlank() {
        assertFalse(handler.canHandle(textMessage("今天天气怎么样")));
        assertFalse(handler.canHandle(textMessage("表格是什么意思")));
        assertFalse(handler.canHandle(textMessage("")));
    }

    @Test
    void doesNotClaimImageOrFileMessages() {
        WeixinMessage image = textMessage("做成表格");
        image.setItem_list(List.of(MessageItem.text("做成表格"), imageItem()));
        assertFalse(handler.canHandle(image));

        WeixinMessage file = textMessage("生成表格");
        file.setItem_list(List.of(MessageItem.text("生成表格"), fileItem()));
        assertFalse(handler.canHandle(file));
    }

    @Test
    void bypassesPlanningIsTrue() {
        assertTrue(handler.bypassesPlanning());
    }

    @Test
    void handlePassesRawTextToSkillAndSendsReply() throws Exception {
        when(skills.execute(eq("excel-operation"), any(SkillRequest.class)))
            .thenReturn(SkillResult.success("✅ 表格已生成（3列×2行）"));
        ILinkClient client = mock(ILinkClient.class);

        handler.handle(client, textMessage(
            "生成覆盖表格：单价,数量,金额\n10,5,=A2*B2\n20,3,=A3*B3"));

        verify(skills).execute(eq("excel-operation"),
            argThat(req -> "user-1".equals(req.userId())
                && req.instruction().contains("=A2*B2")
                && req.instruction().contains("覆盖")));
        verify(client).sendText(eq("user-1"), contains("表格已生成"));
    }

    private MessageItem imageItem() {
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        return item;
    }

    private MessageItem fileItem() {
        MessageItem item = new MessageItem();
        item.setFile_item(new FileItem());
        return item;
    }
}
