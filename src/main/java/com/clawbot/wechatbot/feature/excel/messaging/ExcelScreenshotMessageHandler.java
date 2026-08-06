package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.plan.CreateTableHandler;
import com.clawbot.wechatbot.feature.excel.plan.ExcelOperation;
import com.clawbot.wechatbot.feature.excel.plan.ExcelOperationExecutor;
import com.clawbot.wechatbot.feature.excel.plan.ExcelOperationType;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlan;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanValidator;
import com.clawbot.wechatbot.feature.excel.plan.OperationResult;
import com.clawbot.wechatbot.service.VisionService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表格截图消息处理器：
 *   消息含图片且文本带表格意图关键词（如「表格」「excel」）时，
 *   调用视觉模型把截图中的表格提取为文本 → 组装 CREATE_TABLE 计划
 *   → 走「校验 → 执行」导入到当前活动表（无则新建，截图转表格视为显式替换）。
 *
 * 优先级：低于 ImageMessageHandler(10)，带表格关键词的图片先被本处理器接收；
 * 不含关键词的图片消息仍由 ImageMessageHandler 走通用图片描述。
 */
@Component
public final class ExcelScreenshotMessageHandler implements MessageHandler {

    /** 优先级：小于 ImageMessageHandler(10)，保证带表格关键词的图片先到这里。 */
    private static final int PRIORITY = 5;
    /** 表格意图关键词：消息文本命中任意一个（忽略大小写）即视为想转表格。 */
    private static final List<String> TABLE_KEYWORDS = List.of(
        "表格", "excel", "xlsx", "做成表");
    /** 视觉提取提示词：只输出表格文本，保证 parseTableText 可直接解析。 */
    private static final String EXTRACT_PROMPT =
        "请把图片里的表格提取成纯文本，只输出表格内容，不要输出任何解释文字："
            + "第一行为表头，之后每行一条记录，单元格用逗号或竖线分隔，"
            + "单元格内容里不要出现逗号或竖线。";
    /** 截图转表格默认标题（仅新建表时生效，不影响已有表）。 */
    private static final String TABLE_TITLE = "截图表格";

    private final ExcelService excelService;
    private final VisionService visionService;
    private final ExcelPlanValidator planValidator;
    private final ExcelOperationExecutor executor;

    public ExcelScreenshotMessageHandler(ExcelService excelService,
                                         VisionService visionService) {
        this.excelService = excelService;
        this.visionService = visionService;
        this.planValidator = new ExcelPlanValidator(excelService);
        this.executor = new ExcelOperationExecutor(List.of(
            new CreateTableHandler(excelService)));
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) return false;
        boolean hasImage = false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                hasImage = true;
                break;
            }
        }
        return hasImage && containsTableKeyword(extractText(msg));
    }

    /** 表格截图是强领域意图：图片消息先于统一规划路由处理，避免被 LLM 规划改写/劫持。 */
    @Override
    public boolean bypassesPlanning() {
        return true;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        if (from == null || from.isBlank()) return;

        // 1. 视觉服务未配置 → 提示与 ImageMessageHandler 一致
        if (!visionService.isConfigured()) {
            safeSendText(client, from,
                "（我暂时无法识别图片，请先配置 DASHSCOPE_API_KEY 后再试）");
            return;
        }

        // 2. 下载第一张图片字节（复用 ImageMessageHandler 的下载方式）
        byte[] imageBytes = downloadFirstImage(client, msg);
        if (imageBytes == null || imageBytes.length == 0) {
            safeSendText(client, from, "图片下载失败，请更换图片后重试。");
            return;
        }

        try {
            // 3. 视觉模型严格提取表格文本：首行为表头、每行一条、逗号/竖线分隔
            String tableText = visionService.understandImage(imageBytes, EXTRACT_PROMPT);
            ExcelService.ParsedTable parsed = ExcelService.parseTableText(tableText);
            // 4. 表头为空或没有数据行（如视觉只回了一行解释文字）→ 友好失败
            if (parsed.headers().isEmpty() || parsed.rows().isEmpty()) {
                safeSendText(client, from, "没能从图片里识别出表格，请换张清晰一点的图。");
                return;
            }

            // 5. 组装 CREATE_TABLE 计划并走「校验 → 执行」：
            //    截图转表格总是新建一张「截图表格」并设为活动表，绝不替换任何现有数据
            ExcelTable table = excelService.createWorkbook(from, TABLE_TITLE);
            ExcelPlan plan = buildPlan(from, parsed);
            Optional<String> validationError = planValidator.validate(plan, table);
            if (validationError.isPresent()) {
                safeSendText(client, from, validationError.get());
                return;
            }
            OperationResult result = executor.execute(plan, table);
            if (!result.success()) {
                safeSendText(client, from, result.text());
                return;
            }

            // 6. 成功：回复识别出的行数列数 + 新建说明，附 xlsx 附件
            String reply = "✅ 已从截图识别出 " + parsed.rows().size() + " 行数据（"
                + parsed.headers().size() + "列）"
                + "，已新建「" + TABLE_TITLE + "」并切换为当前表格。";
            safeSendText(client, from, reply);
            sendXlsx(client, from, result.attachment(), table);
        } catch (Exception e) {
            System.err.println("[ERROR] 处理表格截图失败: " + e.getMessage());
            safeSendText(client, from, "❌ 表格识别失败："
                + (e.getMessage() == null ? "未知错误" : e.getMessage())
                + "，请换一张清晰一点的截图。");
        }
    }

    /** 组装 CREATE_TABLE 计划：内容用 Tab 连接，与 parseTableText 往返无损（单元格含逗号也不串列）。 */
    private static ExcelPlan buildPlan(String userId, ExcelService.ParsedTable parsed) {
        StringBuilder rows = new StringBuilder();
        for (List<String> row : parsed.rows()) {
            if (!rows.isEmpty()) rows.append('\n');
            rows.append(String.join("\t", row));
        }
        ExcelOperation operation = new ExcelOperation(
            "1",
            ExcelOperationType.CREATE_TABLE,
            Map.of(
                "headers", String.join("\t", parsed.headers()),
                "rows", rows.toString(),
                "overwrite", "true",
                "title", TABLE_TITLE),
            List.of());
        return new ExcelPlan(userId, List.of(operation));
    }

    /** 取消息文本（仅 text 类型项），去掉 [图片] 等占位符；与 ImageMessageHandler 提取方式一致。 */
    private static String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            }
        }
        return sb.toString()
            .replace("[图片]", "").replace("[语音]", "")
            .replace("[文件]", "").replace("[视频]", "");
    }

    /** 是否命中表格意图关键词（忽略大小写，"Excel"/"XLSX" 均能命中）。 */
    private static boolean containsTableKeyword(String text) {
        String lowered = text.toLowerCase();
        for (String keyword : TABLE_KEYWORDS) {
            if (lowered.contains(keyword)) return true;
        }
        return false;
    }

    /** 下载第一张图片字节（与 ImageMessageHandler 相同的下载方式）。 */
    private byte[] downloadFirstImage(ILinkClient client, WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                try {
                    return client.downloadImageFromMessageItem(item);
                } catch (Exception e) {
                    System.err.println("[ERROR] 图片下载失败: " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    /** 发送 xlsx 附件（附件描述参考技能导出的风格；失败仅告警，不影响主回复）。 */
    private void sendXlsx(ILinkClient client, String from, byte[] bytes, ExcelTable table) {
        if (bytes == null) return;
        try {
            client.sendFile(from, bytes, TABLE_TITLE + ".xlsx",
                "Excel 表格（" + table.getHeaders().size() + "列×"
                    + table.getRows().size() + "行）");
        } catch (Exception e) {
            System.err.println("[WARN] 发送 Excel 附件失败: " + e.getMessage());
        }
    }

    private void safeSendText(ILinkClient client, String from, String text) {
        try {
            if (client != null) client.sendText(from, text);
        } catch (Exception e) {
            System.err.println("[WARN] 发送文字失败: " + e.getMessage());
        }
    }

    @Override
    public int priority() {
        return PRIORITY;  // 低于 ImageMessageHandler(10)，带表格关键词的图片优先到这里
    }
}
