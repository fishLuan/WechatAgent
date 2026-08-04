package com.clawbot.wechatbot.feature.excel;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Excel 表格核心服务：解析表格文本、POI 生成 .xlsx、列聚合查询。 */
@Component
public class ExcelService {

    private static final List<String> DELIMITERS = List.of("\t", "|", ",", ";", "，");
    /** POI 列宽上限（字符单位 * 256）。 */
    private static final int MAX_COLUMN_WIDTH = 255 * 256;

    private final ExcelTableRepository repository;

    public ExcelService(ExcelTableRepository repository) {
        this.repository = repository;
    }

    /** 解析后的表格结构：表头 + 数据行。 */
    public record ParsedTable(List<String> headers, List<List<String>> rows) {
    }

    public enum QueryType {
        MAX("最大值"), MIN("最小值"), SUM("合计"), AVERAGE("平均值"), COUNT("行数");

        private final String label;

        QueryType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /* ========== 表格状态（Mongo 持久化） ========== */

    public ExcelTable loadOrCreate(String userId, String title) {
        Optional<ExcelTable> existing = repository.findByWechatUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ExcelTable table = new ExcelTable(userId, title);
        return repository.save(table);
    }

    public ExcelTable save(ExcelTable table) {
        table.setUpdatedAt(Instant.now());
        return repository.save(table);
    }

    /* ========== 文本解析 ========== */

    /** 把"每行一条、分隔符分隔"的文本解析成表格结构；首行为表头。 */
    public static ParsedTable parseTableText(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedTable(List.of(), List.of());
        }
        List<String> lines = text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        if (lines.isEmpty()) {
            return new ParsedTable(List.of(), List.of());
        }
        String delimiter = detectDelimiter(lines.get(0));
        List<String> headers = splitLine(lines.get(0), delimiter);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = splitLine(lines.get(i), delimiter);
            if (!cells.isEmpty()) {
                rows.add(normalizeCells(cells, headers.size()));
            }
        }
        return new ParsedTable(headers, rows);
    }

    /** 探测行内分隔符：优先 Tab，其次 |、半角逗号、分号，最后全角逗号。 */
    private static String detectDelimiter(String line) {
        for (String candidate : DELIMITERS) {
            if (line.contains(candidate)) {
                return candidate;
            }
        }
        return ",";
    }

    /** 拆分单元格并保留空单元格（"张三,,北京"需解析为三列，空位不能丢弃导致列位错位）。 */
    private static List<String> splitLine(String line, String delimiter) {
        // 负 limit 让 split 保留末尾的空字符串（如 "张三,25," 应拆出 3 个单元格）
        String[] parts = line.split(java.util.regex.Pattern.quote(delimiter), -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    /** 与表头对齐：列数不足补空，超出丢弃。 */
    private static List<String> normalizeCells(List<String> cells, int headerCount) {
        List<String> normalized = new ArrayList<>(cells);
        while (normalized.size() < headerCount) {
            normalized.add("");
        }
        if (normalized.size() > headerCount) {
            normalized = new ArrayList<>(normalized.subList(0, headerCount));
        }
        return normalized;
    }

    /** 把单行数据按表格已用的分隔符拆分为单元格。 */
    public static List<String> splitRowData(String rowText, ExcelTable table) {
        String delimiter = table.getHeaders().isEmpty()
            ? "," : detectDelimiter(String.join(",", table.getHeaders()));
        List<String> cells = splitLine(rowText, delimiter);
        return normalizeCells(cells, table.getHeaders().isEmpty()
            ? cells.size() : table.getHeaders().size());
    }

    /* ========== POI 导出 .xlsx ========== */

    public byte[] toXlsx(ExcelTable table) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet(safeSheetName(table.getTitle()));

            // 表头行（加粗）
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            Row headerRow = sheet.createRow(0);
            List<String> headers = table.getHeaders();
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            // 数据行
            List<List<String>> rows = table.getRows();
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    row.createCell(c).setCellValue(cells.get(c));
                }
            }

            autoSizeColumns(sheet, headers.size(), rows);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 自动列宽：中文字符按 2 个宽度单位估算，避免 POI 原生算法对中文失效。 */
    private static void autoSizeColumns(
        XSSFSheet sheet, int headerCount, List<List<String>> rows
    ) {
        if (headerCount == 0) return;
        int[] widths = new int[headerCount];
        for (int c = 0; c < headerCount; c++) {
            widths[c] = displayWidth(sheet.getRow(0).getCell(c).getStringCellValue());
        }
        for (List<String> cells : rows) {
            for (int c = 0; c < Math.min(cells.size(), headerCount); c++) {
                widths[c] = Math.max(widths[c], displayWidth(cells.get(c)));
            }
        }
        for (int c = 0; c < headerCount; c++) {
            sheet.setColumnWidth(c, Math.min(MAX_COLUMN_WIDTH, widths[c] * 256 + 200));
        }
    }

    private static int displayWidth(String value) {
        if (value == null) return 1;
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            width += value.charAt(i) > 0xFF ? 2 : 1;
        }
        return Math.max(1, width);
    }

    private static String safeSheetName(String title) {
        String name = title.replaceAll("[\\\\/*?:\\[\\]]", "").trim();
        if (name.isBlank()) return "Sheet1";
        return name.length() > 31 ? name.substring(0, 31) : name;
    }

    /* ========== 列聚合查询 ========== */

    /** 查询指定列的聚合值；列不存在或无数值返回错误文案。 */
    public String queryColumn(ExcelTable table, String columnName, QueryType type) {
        List<String> headers = table.getHeaders();
        if (headers.isEmpty()) {
            return "❌ 表格还没有表头，请先生成表格。";
        }
        int columnIndex = findColumnIndex(headers, columnName);
        if (columnIndex < 0) {
            return "❌ 找不到列「" + columnName + "」，现有列：" + String.join("、", headers);
        }
        if (type == QueryType.COUNT) {
            return "📊 " + headers.get(columnIndex) + " 列共有 "
                + table.getRows().size() + " 行数据。";
        }

        List<Double> values = new ArrayList<>();
        for (List<String> cells : table.getRows()) {
            if (columnIndex < cells.size()) {
                Double parsed = parseNumber(cells.get(columnIndex));
                if (parsed != null) {
                    values.add(parsed);
                }
            }
        }
        if (values.isEmpty()) {
            return "❌ 列「" + headers.get(columnIndex)
                + "」没有可计算的数值数据（可能是文本列）。";
        }
        double result = switch (type) {
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case AVERAGE -> values.stream().mapToDouble(Double::doubleValue)
                .average().orElse(0);
            default -> values.size();
        };
        String formatted = type == QueryType.AVERAGE || type == QueryType.SUM
            ? String.format(Locale.ROOT, "%.2f", result)
            : String.valueOf(Math.round(result));
        return "📊 " + headers.get(columnIndex) + " 列的"
            + type.label() + "：" + formatted + "（基于 " + values.size() + " 个数值）";
    }

    private static int findColumnIndex(List<String> headers, String columnName) {
        if (columnName == null || columnName.isBlank()) return -1;
        String target = columnName.trim();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equals(target)) return i;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).contains(target) || target.contains(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 解析数值：兼容 ￥、%、千分位逗号等修饰符。 */
    private static Double parseNumber(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value
            .replace("￥", "").replace("¥", "").replace("%", "")
            .replace(",", "").replace("，", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
