package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanParser;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.skills.SkillManager;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Excel 文字指令消息处理器：纯文字且能被 ExcelPlanParser 识别成操作计划的指令，
 * 直接以用户原文调用 Excel 技能，绕过 LLM 任务规划层。
 *
 * 规划层（LLM）多次以不同方式改写/截断表格指令（吞「覆盖」、改写成「表头为/数据行」、
 * 「覆盖」独占首行、截断多行数据），提示词补丁治标不治本；表格指令是确定性语法，
 * 应直接走解析器原文，避免数据在规划层丢失。
 *
 * 优先级：低于 ExcelScreenshotMessageHandler(5)（图片+文字由截图处理器接管）；
 * 只接管纯文字消息，图片/文件/语音/视频分别走各自的领域处理器。
 */
@Component
public final class ExcelTextMessageHandler implements MessageHandler {

    private static final int PRIORITY = 6;
    private static final String EXCEL_SKILL_NAME = "excel-operation";

    private final SkillManager skills;
    private final ExcelPlanParser planParser = new ExcelPlanParser();

    public ExcelTextMessageHandler(SkillManager skills) {
        this.skills = skills;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    /** Excel 文字指令属于强领域意图：先于统一任务规划直接处理，避免被 LLM 规划改写/截断。 */
    @Override
    public boolean bypassesPlanning() {
        return true;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) return false;
        // 只接管纯文字消息：图片走截图处理器、文件走上传处理器、语音/视频不处理
        if (hasNonTextItem(msg)) return false;
        String text = extractText(msg);
        if (text.isBlank()) return false;
        // 确定性识别：能被 Excel 解析器解析成操作计划才接管，聊天类问题不抢
        String userId = msg.getFrom_user_id() == null ? "" : msg.getFrom_user_id();
        return planParser.parse(userId, text) != null;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        if (from == null || from.isBlank()) return;
        String text = extractText(msg);
        try {
            SkillResult result = skills.execute(EXCEL_SKILL_NAME,
                new SkillRequest(from, text, "", "", ""));
            if (result.success()) {
                safeSendText(client, from, result.text());
                sendAttachments(client, from, result.attachments());
            } else {
                safeSendText(client, from, result.text());
            }
        } catch (Exception error) {
            System.err.println("[ERROR] Excel 文字指令处理失败: " + error.getMessage());
            safeSendText(client, from, "❌ Excel 操作失败："
                + (error.getMessage() == null ? error.toString() : error.getMessage()));
        }
    }

    private void sendAttachments(ILinkClient client, String from,
                                 List<AgentAttachment> attachments) {
        for (AgentAttachment attachment : attachments) {
            try {
                client.sendFile(from, attachment.content(),
                    attachment.fileName(), attachment.caption());
                System.out.println("[INFO] ✅ Excel 附件已发送: "
                    + attachment.fileName());
            } catch (Exception error) {
                System.err.println("[WARN] Excel 附件发送失败: " + error.getMessage());
                safeSendText(client, from, "文字任务已完成，但附件发送失败："
                    + attachment.fileName());
            }
        }
    }

    private void safeSendText(ILinkClient client, String to, String text) {
        try {
            client.sendText(to, text);
        } catch (Exception error) {
            System.err.println("[ERROR] Excel 回复发送失败: " + error.getMessage());
        }
    }

    /** 是否含图片/文件/语音/视频等非文字项（有则交给对应领域处理器）。 */
    private static boolean hasNonTextItem(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item == null) continue;
            if (item.getImage_item() != null
                || item.getFile_item() != null
                || item.getVoice_item() != null
                || item.getVideo_item() != null) {
                return true;
            }
        }
        return false;
    }

    /** 提取消息中的文字内容（过滤 SDK 占位符）。 */
    private static String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item != null && item.getType() == 1
                && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            }
        }
        return sb.toString()
            .replace("[图片]", "").replace("[语音]", "")
            .replace("[文件]", "").replace("[视频]", "");
    }
}
