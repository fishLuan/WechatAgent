package com.clawbot.wechatbot.feature.excel.messaging;

import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 文件消息处理器：
 *   接收用户发来的 .xlsx 文件 → 大小/魔数校验 → POI 解析 → 导入到当前活动表（无则新建）
 *
 * 优先级：高于 DocumentMessageHandler(30)，xlsx 文件先到这里处理，不会走文档总结。
 */
@Component
public final class ExcelFileMessageHandler implements MessageHandler {

    /** 文件大小上限（字节）：10MB。 */
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    /** xlsx 本质是 ZIP 容器，文件头魔数为 PK\x03\x04，用于拒绝伪装扩展名的文件。 */
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    /** 优先级：小于 DocumentMessageHandler(30)，保证 xlsx 文件消息先被本处理器接收。 */
    private static final int PRIORITY = 25;

    private final ExcelService excelService;

    public ExcelFileMessageHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            FileItem fileItem = item.getFile_item();
            if (fileItem != null && isXlsx(fileItem.getFile_name())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        if (from == null || from.isBlank()) return;
        FileItem fileItem = findFileItem(msg);
        String fileName = fileItem == null ? null : fileItem.getFile_name();
        if (fileName == null || fileName.isBlank()) return;

        // 1. 大小校验：优先用消息里携带的 len 字段（字节），超限直接拒绝
        Long declaredSize = parseSize(fileItem.getLen());
        if (declaredSize != null && declaredSize > MAX_FILE_SIZE_BYTES) {
            safeSendText(client, from,
                "❌ 文件「" + fileName + "」超过 10MB 上限，无法导入，请拆分后再试。");
            return;
        }

        safeSendText(client, from, "📄 收到 Excel 文件：" + fileName + "，正在导入...");

        try {
            // 2. 从消息下载文件
            MessageItem msgItem = findFileMessageItem(msg);
            byte[] fileBytes = (msgItem != null)
                ? client.downloadFileFromMessageItem(msgItem) : null;
            if (fileBytes == null || fileBytes.length == 0) {
                safeSendText(client, from, "文件下载失败，请稍后重试。");
                return;
            }
            // 3. 下载后兜底校验大小，并校验 ZIP 魔数（拒绝伪装扩展名的文件）
            if (fileBytes.length > MAX_FILE_SIZE_BYTES) {
                safeSendText(client, from,
                    "❌ 文件「" + fileName + "」超过 10MB 上限，无法导入。");
                return;
            }
            if (!hasZipMagic(fileBytes)) {
                safeSendText(client, from,
                    "❌ 文件「" + fileName + "」内容不是有效的 xlsx"
                        + "（文件头不符合 ZIP 格式），无法导入。");
                return;
            }

            // 4. 解析第一个工作表：第一行为表头，其余为数据行
            ParsedExcel parsed = parseWorkbook(fileBytes);
            if (parsed.headers().isEmpty()
                || parsed.headers().stream().allMatch(String::isBlank)) {
                safeSendText(client, from, "❌ 工作表为空或没有表头行，无法导入。");
                return;
            }

            // 5. 导入到当前活动表（上传导入是显式操作，可替换现有表）；
            //    没有活动表时以文件名新建一张并设为活动表
            ExcelTable table = excelService.getActiveWorkbook(from);
            if (table == null) {
                table = excelService.createWorkbook(from, resolveTitle(fileName));
            }
            boolean replaced = !table.getHeaders().isEmpty() || !table.getRows().isEmpty();
            if (replaced) {
                // 替换前快照，保留原数据以便回滚
                excelService.snapshotVersion(table, "导入替换");
            }
            table.setHeaders(parsed.headers());
            table.setRows(parsed.rows());
            excelService.save(table);

            // 表头预览：全部表头用顿号连接；表头为空时省略该行
            String headerPreview = parsed.headers().isEmpty()
                ? "" : "，表头：" + String.join("、", parsed.headers());
            String reply = "✅ 已导入 " + parsed.rows().size() + " 行数据（"
                + parsed.headers().size() + "列）：" + fileName
                + headerPreview
                + (replaced ? "，并已替换你原来的表格。"
                    : "，可直接用「添加/修改/查询」继续操作。");
            safeSendText(client, from, reply);
        } catch (Exception e) {
            System.err.println("[ERROR] 处理 Excel 文件失败: " + e.getMessage());
            safeSendText(client, from,
                "❌ Excel 文件解析失败：" + e.getMessage()
                    + "。请确认是有效的 .xlsx 文件。");
        }
    }

    /** 解析结果：表头 + 数据行。 */
    private record ParsedExcel(List<String> headers, List<List<String>> rows) {
    }

    /** 取第一个工作表：第一行为表头，其余为数据行；跳过全空行，列数与表头对齐。 */
    private static ParsedExcel parseWorkbook(byte[] fileBytes) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(fileBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            Row headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    headers.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
            }

            List<List<String>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>(headers.size());
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                if (cells.stream().allMatch(String::isBlank)) continue; // 跳过全空行
                rows.add(cells);
            }
            return new ParsedExcel(headers, rows);
        }
    }

    /** 校验 ZIP 魔数：xlsx 是 ZIP 容器，文件头必须是 PK\x03\x04。 */
    private static boolean hasZipMagic(byte[] fileBytes) {
        if (fileBytes.length < ZIP_MAGIC.length) return false;
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (fileBytes[i] != ZIP_MAGIC[i]) return false;
        }
        return true;
    }

    /** 解析文件大小（字节）；len 字段缺失或非法时返回 null，由下载后兜底校验。 */
    private static Long parseSize(String len) {
        if (len == null || len.isBlank()) return null;
        try {
            return Long.parseLong(len.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 表格标题：去掉 .xlsx 后缀的文件名。 */
    private static String resolveTitle(String fileName) {
        String base = fileName;
        if (base.toLowerCase().endsWith(".xlsx")) {
            base = base.substring(0, base.length() - 5);
        }
        return base.isBlank() ? "导入的表格" : base;
    }

    private static boolean isXlsx(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".xlsx");
    }

    private static FileItem findFileItem(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            FileItem fileItem = item.getFile_item();
            if (fileItem != null && isXlsx(fileItem.getFile_name())) {
                return fileItem;
            }
        }
        return null;
    }

    private static MessageItem findFileMessageItem(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            FileItem fileItem = item.getFile_item();
            if (fileItem != null && isXlsx(fileItem.getFile_name())) {
                return item;
            }
        }
        return null;
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
        return PRIORITY;  // 低于 DocumentMessageHandler(30)，xlsx 文件优先到这里
    }
}
