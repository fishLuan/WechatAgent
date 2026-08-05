package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanParser;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.skills.SkillManager;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    /** 强表格意图词：解析器不识别但含这些词时，交给 LLM 翻译成规范指令后真实执行。 */
    private static final List<String> STRONG_EXCEL_WORDS = List.of(
        "表格", "工作簿", "Excel", "excel", "xlsx", "做成表", "导出",
        "图表", "柱状图", "折线图", "饼图", "汇总页", "版本历史",
        "操作日志", "知识库");
    /** LLM 改写提示词：只输出规范指令文本或 UNRECOGNIZED，保留数据/公式/覆盖等关键字。 */
    private static final String TRANSLATE_PROMPT = """
        你是 Excel 表格操作指令翻译器。用户的话可能是模糊的自然语言，把它改写成系统可解析的规范指令。
        只输出改写后的指令文本，不要解释、不要加引号、不要输出 JSON。
        规范指令示例：
        - 生成表格：表头用逗号分隔、每行一条数据（如：生成覆盖表格：姓名,城市\\n张三,北京）
        - 添加行：单元格用逗号分隔（如：添加行：张三,北京）
        - 修改第N行：单元格 / 删除第N行
        - 按某列排序 / 按某列降序 / 按某列去重 / 按某列汇总某列 / 补全某列为值
        - 查询某列的最大值 / 最小值 / 合计 / 平均
        - 导出表格 / 新建表格 X / 查看所有工作簿 / 切换表格 X / 重命名表格 X为Y / 复制表格 X / 删除表格 X
        - 撤销 / 查看版本历史 / 对比上一版 / 查看操作日志
        - 添加知识：类别 内容 / 查看知识 / 删除知识 X
        - 美化表格 / 加标题：X / 冻结首行 / 加筛选
        - 生成柱状图：X,Y / 生成折线图：X,Y / 生成饼图：X,Y / 生成汇总页
        保留原文里的数据、数字、公式（如 =A2*B2）和「覆盖」等关键字，不要增删。
        如果这句话与 Excel 表格操作无关，只输出：UNRECOGNIZED
        """;

    private final SkillManager skills;
    private final DeepSeekClient deepSeekClient;
    private final ExcelPlanParser planParser = new ExcelPlanParser();

    public ExcelTextMessageHandler(SkillManager skills, DeepSeekClient deepSeekClient) {
        this.skills = skills;
        this.deepSeekClient = deepSeekClient;
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
        // 解析器不识别但带强表格意图的，也接管：由 LLM 翻译成规范指令后真实执行
        return planParser.parse(userId, text) != null
            || hasStrongExcelIntent(text);
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        if (from == null || from.isBlank()) return;
        String text = extractText(msg);
        String userId = from;
        if (planParser.parse(userId, text) != null) {
            executeAndReply(client, from, text);
            return;
        }
        if (!hasStrongExcelIntent(text)) {
            return; // canHandle 已保证命中，此为防御
        }
        String rewritten = translateToCanonical(text);
        if (rewritten == null || rewritten.isBlank()) {
            executeAndReply(client, from, text); // 交给技能兜底提示
        } else if ("UNRECOGNIZED".equalsIgnoreCase(rewritten)) {
            safeSendText(client, from, "这句似乎不是表格操作，请换个说法试试。");
        } else if (planParser.parse(userId, rewritten) != null) {
            executeAndReply(client, from, rewritten); // LLM 改写后的规范指令
        } else {
            executeAndReply(client, from, text); // 改写后仍不识别 → 技能兜底
        }
    }

    private void executeAndReply(ILinkClient client, String from, String instruction) {
        try {
            SkillResult result = skills.execute(EXCEL_SKILL_NAME,
                new SkillRequest(from, instruction, "", "", ""));
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

    /** 带强表格意图但解析器未识别：调 LLM 改写成规范指令（改写结果仍走解析器+技能校验执行）。 */
    private String translateToCanonical(String text) {
        try {
            ObjectMapper mapper = deepSeekClient.mapper();
            ArrayNode messages = mapper.createArrayNode();
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", TRANSLATE_PROMPT);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", text);
            JsonNode response = deepSeekClient.chat(
                messages, mapper.createArrayNode(), 0.0);
            return response.path("choices").path(0).path("message")
                .path("content").asText("").trim();
        } catch (Exception error) {
            System.err.println("[WARN] Excel 指令翻译失败: " + error.getMessage());
            return null;
        }
    }

    /** 是否带明显表格意图（用于解析器未识别时的 LLM 兜底接管判定）。 */
    private static boolean hasStrongExcelIntent(String text) {
        if (text == null || text.isBlank()) return false;
        return STRONG_EXCEL_WORDS.stream().anyMatch(text::contains);
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
