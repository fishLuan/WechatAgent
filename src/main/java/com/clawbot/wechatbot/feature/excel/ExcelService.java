package com.clawbot.wechatbot.feature.excel;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import com.clawbot.wechatbot.feature.excel.model.ExcelUserState;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableRepository;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableVersionRepository;
import com.clawbot.wechatbot.feature.excel.repository.ExcelUserStateRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTCatAx;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPieChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPieSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPlotArea;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTValAx;
import org.openxmlformats.schemas.drawingml.x2006.chart.STAxPos;
import org.openxmlformats.schemas.drawingml.x2006.chart.STBarDir;
import org.openxmlformats.schemas.drawingml.x2006.chart.STCrosses;
import org.openxmlformats.schemas.drawingml.x2006.chart.STOrientation;
import org.openxmlformats.schemas.drawingml.x2006.chart.STTickLblPos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Excel 表格核心服务：解析表格文本、POI 生成 .xlsx、列聚合查询。 */
@Component
public class ExcelService {

    private static final List<String> DELIMITERS = List.of("\t", "|", ",", ";", "，");
    /** 表格行数上限：生成/导入/添加行超限一律拒绝（统一提示「表格超出上限」）。 */
    public static final int MAX_TABLE_ROWS = 5000;
    /** 表格列数上限：生成/导入超限一律拒绝（统一提示「表格超出上限」）。 */
    public static final int MAX_TABLE_COLUMNS = 100;
    /** 表格超出上限的统一中文提示：生成/导入/添加行共用。 */
    public static final String TABLE_LIMIT_MESSAGE =
        "表格超出上限（最多 5000 行 / 100 列），请拆分后再试。";
    /** POI 列宽上限（字符单位 * 256）。 */
    private static final int MAX_COLUMN_WIDTH = 255 * 256;
    /** 无歧义日期格式（导出时按日期单元格写入，其他格式一律按文本）。 */
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"));
    /** 版本保留上限：每张表最多 20 条，超出删除最旧的。 */
    private static final int MAX_VERSIONS = 20;
    /** 回滚操作快照的固定说明：回滚时跳过刚写入的这一条，避免快照自身成为回滚目标。 */
    public static final String ROLLBACK_DESCRIPTION = "回滚操作";
    /** 公式长度上限：公式来自微信消息，超长直接拒绝，避免极端输入拖垮正则解析。 */
    private static final int MAX_FORMULA_LENGTH = 200;
    /** 公式中禁止出现的字符：外部引用/超链接/比较运算/注入字符一律拒绝。 */
    private static final String FORMULA_FORBIDDEN = "![]#;<>|&'\\";
    /** 公式函数白名单：仅允许这些函数以「函数名(」形式出现。 */
    private static final List<String> SAFE_FUNCTIONS = List.of(
        "SUM", "AVERAGE", "MAX", "MIN", "COUNT", "COUNTA", "IF", "ROUND",
        "ABS", "INT", "MOD", "TODAY", "NOW", "CONCATENATE");
    /** 公式 token 正则：数字/四则与幂运算/括号/逗号/冒号/字符串字面量/单元格引用/函数名+左括号；token 前可带空白。 */
    private static final java.util.regex.Pattern FORMULA_TOKEN = java.util.regex.Pattern.compile(
        "\\s*(?:"
            + "\\d+(?:\\.\\d+)?|\\.\\d+"   // 数字：123 / 1.5 / .5
            + "|[+\\-*/^(),:]"             // 四则运算、幂、括号、逗号、冒号（范围引用）
            + "|\"[^\"]*\""                // 双引号字符串字面量（不含内部转义引号）
            + "|[A-Z]{1,3}[0-9]{1,7}"      // 单元格引用：A1 / AB12
            + "|[A-Za-z]+\\s*\\("          // 函数调用：函数名 + 左括号（名称另行白名单校验）
            + ")");

    private final ExcelTableRepository repository;
    private final ExcelTableVersionRepository versionRepository;
    private final ExcelUserStateRepository stateRepository;

    /** 测试/旧场景构造器：不注入用户状态仓库（工作簿管理方法不可用）。 */
    public ExcelService(ExcelTableRepository repository,
                        ExcelTableVersionRepository versionRepository) {
        this(repository, versionRepository, null);
    }

    /** Spring 装配：注入用户状态仓库，提供多工作簿（活动表）管理能力。 */
    @Autowired
    public ExcelService(ExcelTableRepository repository,
                        ExcelTableVersionRepository versionRepository,
                        ExcelUserStateRepository stateRepository) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.stateRepository = stateRepository;
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

    /** 取或建（旧「一张表」语义，多工作簿后不再使用；取该用户第一张表）。 */
    public ExcelTable loadOrCreate(String userId, String title) {
        List<ExcelTable> existing = repository.findByWechatUserId(userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ExcelTable table = new ExcelTable(userId, title);
        return repository.save(table);
    }

    public ExcelTable save(ExcelTable table) {
        table.setUpdatedAt(Instant.now());
        return repository.save(table);
    }

    /**
     * 存量数据回填：多工作簿上线前，老用户可能已有表但没有 excel_user_state 记录。
     * 启动时把这类用户"最早创建的表"设为活动表，避免老用户收到"还没有表格"提示。
     */
    @PostConstruct
    void backfillActiveWorkbooks() {
        if (stateRepository == null) {
            return; // 测试/旧构造器场景
        }
        Set<String> usersWithState = new HashSet<>();
        for (ExcelUserState state : stateRepository.findAll()) {
            usersWithState.add(state.getWechatUserId());
        }
        repository.findAll().stream()
            .sorted(Comparator.comparing(ExcelTable::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .forEach(table -> {
                if (!usersWithState.contains(table.getWechatUserId())) {
                    setActiveWorkbook(table.getWechatUserId(), table);
                    usersWithState.add(table.getWechatUserId());
                }
            });
    }

    /* ========== 工作簿管理（多表，按活动表定位） ========== */

    /**
     * 读用户状态返回当前活动表；无状态记录、状态无活动表或表已不存在时返回 null。
     * （活动表被删除时状态一并置空，此分支仅兜底历史脏数据）
     */
    public ExcelTable getActiveWorkbook(String wechatUserId) {
        Optional<ExcelUserState> state = stateRepository.findByWechatUserId(wechatUserId);
        if (state.isEmpty() || state.get().getActiveWorkbookId() == null
            || state.get().getActiveWorkbookId().isBlank()) {
            return null;
        }
        return repository.findById(state.get().getActiveWorkbookId()).orElse(null);
    }

    /** 保存活动表（无状态记录时新建，否则更新），返回该表。 */
    public ExcelTable setActiveWorkbook(String wechatUserId, ExcelTable table) {
        if (table == null || table.getId() == null || table.getId().isBlank()) {
            return table;
        }
        ExcelUserState state = stateRepository.findByWechatUserId(wechatUserId)
            .orElseGet(() -> new ExcelUserState(wechatUserId));
        state.setActiveWorkbookId(table.getId());
        stateRepository.save(state);
        return table;
    }

    /** 新建一张表并设为活动表。 */
    public ExcelTable createWorkbook(String wechatUserId, String title) {
        ExcelTable table = repository.save(new ExcelTable(wechatUserId, title));
        setActiveWorkbook(wechatUserId, table);
        return table;
    }

    /** 某用户的全部表格，按创建时间升序（创建顺序）。 */
    public List<ExcelTable> listWorkbooks(String wechatUserId) {
        return repository.findByWechatUserId(wechatUserId).stream()
            .sorted(Comparator.comparing(ExcelTable::getCreatedAt))
            .toList();
    }

    /** 按 id 取表并校验归属：不是该用户的表（或不存在）返回 null。 */
    public ExcelTable findWorkbookById(String wechatUserId, String workbookId) {
        if (workbookId == null || workbookId.isBlank()) {
            return null;
        }
        Optional<ExcelTable> table = repository.findById(workbookId);
        if (table.isEmpty() || !wechatUserId.equals(table.get().getWechatUserId())) {
            return null;
        }
        return table.get();
    }

    /** 改标题；表不存在或非该用户所有时返回 empty。 */
    public Optional<ExcelTable> renameWorkbook(String wechatUserId, String workbookId,
                                               String newTitle) {
        ExcelTable table = findWorkbookById(wechatUserId, workbookId);
        if (table == null) {
            return Optional.empty();
        }
        table.setTitle(newTitle);
        return Optional.of(repository.save(table));
    }

    /** 删除表 + 其全部版本快照；删除的是活动表时活动状态置空；表不存在或非该用户所有时返回 false。 */
    public boolean deleteWorkbook(String wechatUserId, String workbookId) {
        ExcelTable table = findWorkbookById(wechatUserId, workbookId);
        if (table == null) {
            return false;
        }
        // 级联删除该表全部版本快照（版本按 tableId 隔离，与回滚互不影响）
        List<ExcelTableVersion> versions =
            versionRepository.findByTableIdOrderByCreatedAtDesc(table.getId());
        versionRepository.deleteAll(versions);
        repository.delete(table);
        // 删除的是活动表时，活动状态置空（此后指令提示先建表）
        stateRepository.findByWechatUserId(wechatUserId).ifPresent(state -> {
            if (workbookId.equals(state.getActiveWorkbookId())) {
                state.setActiveWorkbookId(null);
                stateRepository.save(state);
            }
        });
        return true;
    }

    /** 复制表（新 id、标题加「副本」，不含版本历史）并设为活动表；源表不存在或非该用户所有时返回 empty。 */
    public Optional<ExcelTable> copyWorkbook(String wechatUserId, String workbookId) {
        ExcelTable source = findWorkbookById(wechatUserId, workbookId);
        if (source == null) {
            return Optional.empty();
        }
        ExcelTable copy = new ExcelTable(wechatUserId, source.getTitle() + "副本");
        copy.setHeaders(source.getHeaders());
        copy.setRows(source.getRows());
        ExcelTable saved = repository.save(copy);
        setActiveWorkbook(wechatUserId, saved);
        return Optional.of(saved);
    }

    /* ========== 版本快照与回滚 ========== */

    /**
     * 把表当前状态深拷贝存为一条版本记录；新增后清理超出上限的旧版本。
     * （headers/rows 经 setHeaders/setRows 防御性复制，不共享引用）
     */
    public void snapshotVersion(ExcelTable table, String description) {
        if (table == null || table.getId() == null || table.getId().isBlank()) {
            return;
        }
        ExcelTableVersion version = new ExcelTableVersion(
            table.getId(), table.getHeaders(), table.getRows(), description);
        versionRepository.save(version);
        // 版本保留上限：按创建时间倒序保留最新 MAX_VERSIONS 条，删除更旧的
        List<ExcelTableVersion> all =
            versionRepository.findByTableIdOrderByCreatedAtDesc(table.getId());
        if (all.size() > MAX_VERSIONS) {
            versionRepository.deleteAll(all.subList(MAX_VERSIONS, all.size()));
        }
    }

    /**
     * 取该表最新版本恢复到表格对象上；返回被恢复的版本（调用方在导出并保存成功后 consumeVersion 消费）。
     * 若最新一条是刚写入的「回滚操作」快照则跳过它（回滚目标应是最近一次变更前的状态）；
     * 恢复不删除版本：导出失败时版本保留、表格不落库，重试仍可回到同一目标。
     */
    public ExcelTableVersion restoreLatestVersion(ExcelTable table) {
        if (table == null || table.getId() == null || table.getId().isBlank()) {
            return null;
        }
        List<ExcelTableVersion> versions =
            versionRepository.findByTableIdOrderByCreatedAtDesc(table.getId());
        if (versions.isEmpty()) {
            return null;
        }
        ExcelTableVersion latest = versions.get(0);
        if (ROLLBACK_DESCRIPTION.equals(latest.getDescription()) && versions.size() > 1) {
            latest = versions.get(1);
        }
        table.setHeaders(latest.getHeaders());
        table.setRows(latest.getRows());
        return latest;
    }

    /** 消费一条已成功恢复的版本记录（回滚导出并保存成功后删除，避免下一次回滚到同一状态）。 */
    public void consumeVersion(ExcelTableVersion version) {
        if (version == null || version.getId() == null) {
            return;
        }
        versionRepository.delete(version);
    }

    /** 某表当前版本数量。 */
    public long versionCount(ExcelTable table) {
        if (table == null || table.getId() == null || table.getId().isBlank()) {
            return 0;
        }
        return versionRepository.countByTableId(table.getId());
    }

    /** 某表最近若干条版本（最新在前），供版本历史回复使用。 */
    public List<ExcelTableVersion> recentVersions(ExcelTable table, int limit) {
        if (table == null || table.getId() == null || table.getId().isBlank()) {
            return List.of();
        }
        return versionRepository.findByTableIdOrderByCreatedAtDesc(table.getId())
            .stream().limit(limit).toList();
    }

    /**
     * 版本对比：取该表最新版本快照与当前表对比，输出中文摘要（表头是否有变化 +
     * 新增/删除/修改行数）。没有版本快照时返回「还没有可对比的版本」。
     * 行以单元格序列（整行内容）为键做集合差：新增=当前有而版本无，删除=版本有而当前无；
     * 修改=两表同行号但内容不同的位置数（简化实现，与新增/删除可叠加统计）。
     */
    public String diffVersions(ExcelTable table) {
        if (table == null || table.getId() == null || table.getId().isBlank()) {
            return "还没有可对比的版本";
        }
        List<ExcelTableVersion> versions =
            versionRepository.findByTableIdOrderByCreatedAtDesc(table.getId());
        if (versions.isEmpty()) {
            return "还没有可对比的版本";
        }
        ExcelTableVersion latest = versions.get(0);
        List<List<String>> versionRows = latest.getRows();
        Set<List<String>> currentKeys = new HashSet<>(table.getRows());
        Set<List<String>> versionKeys = new HashSet<>(versionRows);
        int added = 0;
        for (List<String> row : currentKeys) {
            if (!versionKeys.contains(row)) {
                added++;
            }
        }
        int removed = 0;
        for (List<String> row : versionKeys) {
            if (!currentKeys.contains(row)) {
                removed++;
            }
        }
        // 修改：两表同行号但内容不同的位置数（只比较两表都有的行号区间，简化实现）
        int modified = 0;
        int compareCount = Math.min(table.getRows().size(), versionRows.size());
        for (int i = 0; i < compareCount; i++) {
            if (!table.getRows().get(i).equals(versionRows.get(i))) {
                modified++;
            }
        }
        boolean headersChanged = !table.getHeaders().equals(latest.getHeaders());
        return "📊 与上一版本对比：" + (headersChanged ? "表头有变化" : "表头无变化")
            + "；新增 " + added + " 行 / 删除 " + removed
            + " 行 / 修改 " + modified + " 行。";
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
        try (XSSFWorkbook workbook = buildWorkbook(table);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeWorkbook(workbook, out);
            return out.toByteArray();
        }
    }

    /**
     * 构建工作簿的数据工作表（toXlsx / 图表 / 汇总页共用）：
     * titleRow 非空时第 0 行为合并单元格标题（加粗 14 号字），表头行从第 1 行开始；
     * 表头加粗、数据按值类型写入、自动列宽；freezeHeader 冻结表头行、autoFilter 对表头+数据范围筛选。
     */
    private static XSSFWorkbook buildWorkbook(ExcelTable table) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(safeSheetName(table.getTitle()));
        List<String> headers = table.getHeaders();
        List<List<String>> rows = table.getRows();
        // 表标题行：跨全部列合并、加粗 14 号字；存在时表头行从第 1 行开始
        int headerRowIndex = 0;
        String titleRow = table.getTitleRow();
        if (titleRow != null && !titleRow.isBlank()) {
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            Row title = sheet.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(titleRow);
            titleCell.setCellStyle(titleStyle);
            if (!headers.isEmpty()) {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
            }
            headerRowIndex = 1;
        }

        // 表头行（加粗）
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        Row headerRow = sheet.createRow(headerRowIndex);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }

        // 数据行：按值类型写入（数字/布尔/无歧义日期推断为类型单元格，其余按文本）
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(headerRowIndex + r + 1);
            List<String> cells = rows.get(r);
            for (int c = 0; c < cells.size(); c++) {
                writeValueCell(row.createCell(c), cells.get(c), dateStyle);
            }
        }

        autoSizeColumns(sheet, headers.size(), rows, headerRowIndex);

        // 冻结表头行（createFreezePane 冻结 表头行号 之前的所有行；有标题行时标题一并冻结）
        if (table.isFreezeHeader()) {
            sheet.createFreezePane(0, headerRowIndex + 1);
        }
        // 自动筛选：表头 + 数据范围
        if (table.isAutoFilter() && !headers.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(
                headerRowIndex, headerRowIndex + rows.size(), 0, headers.size() - 1));
        }
        return workbook;
    }

    /** 写工作簿：强制重算、公式求值检查（存在公式错误则取消导出）、写出字节流。 */
    private static void writeWorkbook(XSSFWorkbook workbook, ByteArrayOutputStream out)
        throws IOException {
        // 公式支持：强制 Excel 打开时重算；导出前先求值，存在公式错误则取消导出
        workbook.setForceFormulaRecalculation(true);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        evaluator.evaluateAll();
        Cell errorCell = findFirstErrorCell(workbook);
        if (errorCell != null) {
            throw new IllegalArgumentException(
                "❌ 公式存在错误，已取消导出：单元格 " + errorCell.getAddress().formatAsString()
                    + " 为 " + FormulaError.forInt(errorCell.getErrorCellValue()).getString()
                    + "。请检查公式后重试。"
                    + "如公式使用了区域，请写成 =SUM(A1:B2) 这类函数形式。");
        }
        workbook.write(out);
    }

    /**
     * 导出带图表的 .xlsx：数据工作表 + 「图表」工作表（分类/数值解析后写入图表工作表，用 XSSF 图表 API 绘制）。
     * 图表数据较少（解析出的分类不足 2 条）时抛出 IllegalArgumentException；不修改表格数据。
     */
    public byte[] toXlsxWithChart(ExcelTable table, String chartType,
                                  String categoryColumn, String valueColumn) throws IOException {
        try (XSSFWorkbook workbook = buildWorkbook(table);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int categoryIndex = findColumnIndex(table.getHeaders(), categoryColumn);
            int valueIndex = findColumnIndex(table.getHeaders(), valueColumn);
            // 解析图表数据：数值列非数值按 0 处理；空分类跳过
            List<String> categories = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            for (List<String> row : table.getRows()) {
                String category = categoryIndex < row.size() ? row.get(categoryIndex) : "";
                if (category == null || category.isBlank()) {
                    continue;
                }
                Double value = valueIndex < row.size()
                    ? parseNumber(row.get(valueIndex)) : null;
                categories.add(category);
                values.add(value == null ? 0.0 : value);
            }
            if (categories.size() < 2) {
                throw new IllegalArgumentException("图表数据不足，请确认分类列和数值列。");
            }
            // 表格标题恰好为「图表」时工作表名冲突，加后缀避免 createSheet 抛异常
            String chartSheetName = "图表".equals(safeSheetName(table.getTitle())) ? "图表2" : "图表";
            XSSFSheet chartSheet = workbook.createSheet(chartSheetName);
            buildChartSheet(chartSheet, chartType, categories, values,
                categoryColumn, valueColumn, chartSheetName);
            writeWorkbook(workbook, out);
            return out.toByteArray();
        }
    }

    /** 单张图表的规格（多图表导出用）。 */
    public record ChartSpec(String chartType, String categoryColumn, String valueColumn) {}

    /**
     * 导出带多张图表的 .xlsx：每张图表各占一个工作表（「图表」「图表2」…），
     * 与数据工作表同名冲突时自动加后缀；任一张图数据不足即整体失败。
     */
    public byte[] toXlsxWithCharts(ExcelTable table, List<ChartSpec> charts) throws IOException {
        if (charts == null || charts.isEmpty()) {
            throw new IllegalArgumentException("图表列表为空。");
        }
        try (XSSFWorkbook workbook = buildWorkbook(table);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Set<String> usedNames = new HashSet<>();
            usedNames.add(safeSheetName(table.getTitle()));
            for (int i = 0; i < charts.size(); i++) {
                ChartSpec chart = charts.get(i);
                int categoryIndex = findColumnIndex(table.getHeaders(), chart.categoryColumn());
                int valueIndex = findColumnIndex(table.getHeaders(), chart.valueColumn());
                List<String> categories = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (List<String> row : table.getRows()) {
                    String category = categoryIndex < row.size() ? row.get(categoryIndex) : "";
                    if (category == null || category.isBlank()) continue;
                    Double value = valueIndex < row.size()
                        ? parseNumber(row.get(valueIndex)) : null;
                    categories.add(category);
                    values.add(value == null ? 0.0 : value);
                }
                if (categories.size() < 2) {
                    throw new IllegalArgumentException(
                        "图表「" + chart.categoryColumn() + "/" + chart.valueColumn()
                            + "」数据不足，请确认分类列和数值列。");
                }
                String baseName = i == 0 ? "图表" : "图表" + (i + 1);
                String sheetName = baseName;
                while (!usedNames.add(sheetName)) {
                    sheetName = sheetName + "2";
                }
                XSSFSheet chartSheet = workbook.createSheet(sheetName);
                buildChartSheet(chartSheet, chart.chartType(), categories, values,
                    chart.categoryColumn(), chart.valueColumn(), sheetName);
            }
            writeWorkbook(workbook, out);
            return out.toByteArray();
        }
    }

    /**
     * 导出带汇总页的 .xlsx：数据工作表 + 「汇总」工作表（表标题 + 列数/行数 + 每列数值型合计与平均，
     * 非数值列标「-」+ 简单说明文本）；不修改表格数据。
     */
    public byte[] toXlsxWithDashboard(ExcelTable table) throws IOException {
        try (XSSFWorkbook workbook = buildWorkbook(table);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 表格标题恰好为「汇总」时工作表名冲突，加后缀避免 createSheet 抛异常
            String sheetName = "汇总".equals(safeSheetName(table.getTitle())) ? "汇总2" : "汇总";
            buildDashboardSheet(workbook, table, sheetName);
            writeWorkbook(workbook, out);
            return out.toByteArray();
        }
    }

    /** 图表工作表：A 列分类、B 列数值（首行为表头），图表引用该区域并按类型绘制柱状/折线/饼图。 */
    private static void buildChartSheet(XSSFSheet chartSheet, String chartType,
                                        List<String> categories, List<Double> values,
                                        String categoryColumn, String valueColumn,
                                        String chartSheetName) {
        Row header = chartSheet.createRow(0);
        header.createCell(0).setCellValue(categoryColumn);
        header.createCell(1).setCellValue(valueColumn);
        for (int i = 0; i < categories.size(); i++) {
            Row row = chartSheet.createRow(i + 1);
            row.createCell(0).setCellValue(categories.get(i));
            row.createCell(1).setCellValue(values.get(i));
        }
        // 图表放在数据区右侧（列 D 起），标题为「按X统计Y」
        XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 3, 0, 16, 24);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("按" + categoryColumn + "统计" + valueColumn);
        chart.setTitleOverlay(false);
        String seriesNameRef = "'" + chartSheetName + "'!$B$1";
        String catRef = "'" + chartSheetName + "'!$A$2:$A$" + (categories.size() + 1);
        String valRef = "'" + chartSheetName + "'!$B$2:$B$" + (values.size() + 1);
        CTPlotArea plotArea = chart.getCTChart().getPlotArea();
        switch (chartType) {
            case "BAR" -> buildBarChart(plotArea, catRef, valRef, seriesNameRef);
            case "LINE" -> buildLineChart(plotArea, catRef, valRef, seriesNameRef);
            case "PIE" -> buildPieChart(plotArea, catRef, valRef, seriesNameRef);
            default -> throw new IllegalArgumentException(
                "非法计划：chartType「" + chartType + "」无效，应为 BAR/LINE/PIE。");
        }
    }

    /** 柱状图：类别轴 + 数值轴（与折线图共用坐标轴构造）。 */
    private static void buildBarChart(CTPlotArea plotArea, String catRef, String valRef,
                                      String seriesNameRef) {
        CTBarChart barChart = plotArea.addNewBarChart();
        barChart.addNewBarDir().setVal(STBarDir.COL);
        CTBarSer ser = barChart.addNewSer();
        ser.addNewIdx().setVal(0);
        ser.addNewOrder().setVal(0);
        ser.addNewTx().addNewStrRef().setF(seriesNameRef);
        ser.addNewCat().addNewStrRef().setF(catRef);
        ser.addNewVal().addNewNumRef().setF(valRef);
        barChart.addNewAxId().setVal(123456);
        barChart.addNewAxId().setVal(123457);
        addChartAxes(plotArea);
    }

    /** 折线图：类别轴 + 数值轴。 */
    private static void buildLineChart(CTPlotArea plotArea, String catRef, String valRef,
                                       String seriesNameRef) {
        CTLineChart lineChart = plotArea.addNewLineChart();
        CTLineSer ser = lineChart.addNewSer();
        ser.addNewIdx().setVal(0);
        ser.addNewOrder().setVal(0);
        ser.addNewTx().addNewStrRef().setF(seriesNameRef);
        ser.addNewCat().addNewStrRef().setF(catRef);
        ser.addNewVal().addNewNumRef().setF(valRef);
        lineChart.addNewAxId().setVal(123456);
        lineChart.addNewAxId().setVal(123457);
        addChartAxes(plotArea);
    }

    /** 饼图：无需坐标轴（类别 + 数值即可）。 */
    private static void buildPieChart(CTPlotArea plotArea, String catRef, String valRef,
                                      String seriesNameRef) {
        CTPieChart pieChart = plotArea.addNewPieChart();
        pieChart.addNewVaryColors().setVal(true);
        CTPieSer ser = pieChart.addNewSer();
        ser.addNewIdx().setVal(0);
        ser.addNewOrder().setVal(0);
        ser.addNewTx().addNewStrRef().setF(seriesNameRef);
        ser.addNewCat().addNewStrRef().setF(catRef);
        ser.addNewVal().addNewNumRef().setF(valRef);
    }

    /** 柱状/折线图共用的坐标轴：数值轴（左侧 L）+ 类别轴（底部 B），id 与图表内引用一致。 */
    private static void addChartAxes(CTPlotArea plotArea) {
        CTValAx valAx = plotArea.addNewValAx();
        valAx.addNewAxId().setVal(123457);
        valAx.addNewCrossAx().setVal(123456);
        valAx.addNewScaling().addNewOrientation().setVal(STOrientation.MIN_MAX);
        valAx.addNewDelete().setVal(false);
        valAx.addNewAxPos().setVal(STAxPos.L);
        valAx.addNewCrosses().setVal(STCrosses.AUTO_ZERO);
        CTCatAx catAx = plotArea.addNewCatAx();
        catAx.addNewAxId().setVal(123456);
        catAx.addNewScaling().addNewOrientation().setVal(STOrientation.MIN_MAX);
        catAx.addNewDelete().setVal(false);
        catAx.addNewAxPos().setVal(STAxPos.B);
        catAx.addNewCrossAx().setVal(123457);
        catAx.addNewCrosses().setVal(STCrosses.AUTO_ZERO);
        catAx.addNewTickLblPos().setVal(STTickLblPos.NEXT_TO);
    }

    /** 汇总工作表：表标题 + 列数/行数 + 每列数值型合计与平均（非数值列标「-」）+ 简单说明文本。 */
    private static void buildDashboardSheet(XSSFWorkbook workbook, ExcelTable table,
                                            String sheetName) {
        XSSFSheet summary = workbook.createSheet(sheetName);
        List<String> headers = table.getHeaders();
        List<List<String>> rows = table.getRows();
        // 表标题（工作表名）+ 说明：第 0 行合并标题、第 1 行行列数
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);
        Row title = summary.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue(table.getTitle() + " 汇总");
        titleCell.setCellStyle(titleStyle);
        if (!headers.isEmpty()) {
            summary.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.min(2, headers.size() - 1)));
        }
        Row info = summary.createRow(1);
        info.createCell(0).setCellValue("共 " + headers.size() + " 列 × " + rows.size() + " 行数据");
        // 表头：列名 | 合计 | 平均
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        Row summaryHeader = summary.createRow(2);
        summaryHeader.createCell(0).setCellValue("列名");
        summaryHeader.createCell(1).setCellValue("合计");
        summaryHeader.createCell(2).setCellValue("平均");
        for (int c = 0; c < 3; c++) {
            summaryHeader.getCell(c).setCellStyle(headerStyle);
        }
        // 每列合计与平均：仅统计数值型单元格（精确累加用 BigDecimal），非数值列标「-」
        for (int c = 0; c < headers.size(); c++) {
            Row row = summary.createRow(3 + c);
            row.createCell(0).setCellValue(headers.get(c));
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (List<String> dataRow : rows) {
                if (c < dataRow.size()) {
                    BigDecimal decimal = parseDecimal(dataRow.get(c));
                    if (decimal != null) {
                        sum = sum.add(decimal);
                        count++;
                    }
                }
            }
            if (count == 0) {
                row.createCell(1).setCellValue("-");
                row.createCell(2).setCellValue("-");
            } else {
                row.createCell(1).setCellValue(sum.doubleValue());
                row.createCell(2).setCellValue(
                    sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP).doubleValue());
            }
        }
        // 简单说明文本
        Row note = summary.createRow(3 + headers.size());
        note.createCell(0).setCellValue(
            "说明：本页由表格自动生成，合计与平均仅统计数值型单元格，非数值列显示「-」。");
        // 汇总页列宽：中文按 2 个宽度单位估算（与主表自动列宽一致）
        int[] widths = new int[]{0, 0, 0};
        for (Row row : summary) {
            for (int c = 0; c < 3; c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.STRING) {
                    widths[c] = Math.max(widths[c], displayWidth(cell.getStringCellValue()));
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            summary.setColumnWidth(c, Math.min(MAX_COLUMN_WIDTH, widths[c] * 256 + 200));
        }
    }

    /**
     * 数据单元格类型推断后写入（存储仍为字符串，仅在导出时推断）：
     * 公式（以 = 开头且通过安全校验）→公式单元格（优先级最高，含 =1+1 这类纯数字公式）；
     * 空字符串→空单元格；数字（Long/Double 解析 + 往返校验，避免前导零/尾零文本被转数字）；
     * true/false（忽略大小写）→布尔；无歧义日期（yyyy-MM-dd / yyyy/MM/dd / yyyy年M月d日）→日期；
     * 其余一律按文本。
     */
    private static void writeValueCell(Cell cell, String raw, CellStyle dateStyle) {
        if (raw == null || raw.isEmpty()) {
            return; // 空字符串按空单元格处理
        }
        // 公式优先：以 = 开头且通过安全校验 → 公式单元格（优先级高于数字/布尔/日期推断）
        if (raw.startsWith("=") && isSafeFormula(raw.substring(1))) {
            String formula = raw.substring(1);
            try {
                cell.setCellFormula(formula);
            } catch (RuntimeException error) {
                // 个别 token 合法但 POI 无法解析的公式（如 A1B2、A1(）按文本写入，与非法公式保持一致
                cell.setCellValue(raw);
            }
            return;
        }
        Long longValue = parseLongExact(raw);
        if (longValue != null) {
            cell.setCellValue(longValue);
            return;
        }
        Double doubleValue = parseDoubleExact(raw);
        if (doubleValue != null) {
            cell.setCellValue(doubleValue);
            return;
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            cell.setCellValue(Boolean.parseBoolean(raw));
            return;
        }
        LocalDate date = parseUnambiguousDate(raw);
        if (date != null) {
            // POI 日期基准为 1900 系统，按系统时区取当日零点避免偏移
            cell.setCellValue(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            cell.setCellStyle(dateStyle);
            return;
        }
        cell.setCellValue(raw);
    }

    /**
     * 公式安全校验（入参不含前导 =）：先按字符黑名单拒绝外部引用/超链接/注入类字符与非 ASCII，
     * 再按 token 校验——token 序列须完整覆盖整个公式串，任何未识别的字符段都判为非法；
     * 函数仅允许白名单（如 HYPERLINK(、foo( 拒绝），字母序列后无左括号（如 =hello）也判为非法。
     */
    private static boolean isSafeFormula(String formula) {
        if (formula == null) {
            return false;
        }
        formula = formula.trim();
        if (formula.isEmpty() || formula.length() > MAX_FORMULA_LENGTH) {
            return false;
        }
        for (int i = 0; i < formula.length(); i++) {
            char ch = formula.charAt(i);
            if (ch > 0x7F || FORMULA_FORBIDDEN.indexOf(ch) >= 0) {
                return false;
            }
        }
        java.util.regex.Matcher matcher = FORMULA_TOKEN.matcher(formula);
        int pos = 0;
        while (pos < formula.length()) {
            if (!matcher.find(pos) || matcher.start() != pos) {
                return false; // 存在无法识别的字符段
            }
            String token = matcher.group();
            if (token.endsWith("(")) {
                // 函数调用 token 必须以白名单函数名开头；单独的左括号（运算符）无需校验
                String name = token.substring(0, token.length() - 1)
                    .trim().toUpperCase(Locale.ROOT);
                if (!name.isEmpty() && !SAFE_FUNCTIONS.contains(name)) {
                    return false; // 非白名单函数拒绝
                }
            }
            pos = matcher.end();
        }
        return true;
    }

    /** 遍历所有工作表，返回第一个公式错误单元格（如 #DIV/0!、#REF!、#NAME?）。 */
    private static Cell findFirstErrorCell(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA
                        && cell.getCachedFormulaResultType() == CellType.ERROR) {
                        return cell;
                    }
                }
            }
        }
        return null;
    }

    /** 整数解析并往返校验：仅当 Long 转回字符串与原值一致时才视为数字（"007" 保持文本）。 */
    private static Long parseLongExact(String raw) {
        String value = raw.trim();
        try {
            long parsed = Long.parseLong(value);
            return Long.toString(parsed).equals(value) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 小数解析并往返校验：仅当 Double 转回字符串与原值一致时才视为数字（"1.50" 保持文本）。 */
    private static Double parseDoubleExact(String raw) {
        String value = raw.trim();
        try {
            double parsed = Double.parseDouble(value);
            return Double.toString(parsed).equals(value) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 无歧义日期解析：仅支持三种格式，其余返回 null 保持文本。 */
    private static LocalDate parseUnambiguousDate(String raw) {
        String value = raw.trim();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    /** 自动列宽：中文字符按 2 个宽度单位估算，避免 POI 原生算法对中文失效。 */
    private static void autoSizeColumns(
        XSSFSheet sheet, int headerCount, List<List<String>> rows, int headerRowIndex
    ) {
        if (headerCount == 0) return;
        int[] widths = new int[headerCount];
        for (int c = 0; c < headerCount; c++) {
            widths[c] = displayWidth(
                sheet.getRow(headerRowIndex).getCell(c).getStringCellValue());
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
        BigDecimal sum = BigDecimal.ZERO;
        for (List<String> cells : table.getRows()) {
            if (columnIndex < cells.size()) {
                String cellValue = cells.get(columnIndex);
                Double parsed = parseNumber(cellValue);
                if (parsed != null) {
                    values.add(parsed);
                    // SUM/AVERAGE 用解析后的字符串构造 BigDecimal，避免浮点误差（如 0.1+0.2）
                    BigDecimal decimal = parseDecimal(cellValue);
                    if (decimal != null) {
                        sum = sum.add(decimal);
                    }
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
            default -> 0;
        };
        String formatted;
        if (type == QueryType.SUM) {
            formatted = String.format(Locale.ROOT, "%.2f", sum);
        } else if (type == QueryType.AVERAGE) {
            formatted = String.format(Locale.ROOT, "%.2f",
                sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP));
        } else if (type == QueryType.MAX || type == QueryType.MIN) {
            formatted = String.valueOf(Math.round(result));
        } else {
            formatted = String.valueOf(values.size());
        }
        return "📊 " + headers.get(columnIndex) + " 列的"
            + type.label() + "：" + formatted + "（基于 " + values.size() + " 个数值）";
    }

    /** 列定位：先精确匹配、再模糊包含匹配（校验器与分析操作复用）；找不到返回 -1。 */
    public static int findColumnIndex(List<String> headers, String columnName) {
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

    /** 解析数值：兼容 ￥、%、千分位逗号等修饰符（排序/分组汇总等分析操作复用）。 */
    public static Double parseNumber(String value) {
        String cleaned = cleanNumberText(value);
        if (cleaned == null) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 解析为 BigDecimal（SUM/AVERAGE 精确累加用），失败返回 null（分组汇总复用）。 */
    public static BigDecimal parseDecimal(String value) {
        String cleaned = cleanNumberText(value);
        if (cleaned == null) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 清理数值文本：兼容 ￥、%、千分位逗号等修饰符；空文本返回 null。 */
    private static String cleanNumberText(String value) {
        if (value == null || value.isBlank()) return null;
        return value
            .replace("￥", "").replace("¥", "").replace("%", "")
            .replace(",", "").replace("，", "").trim();
    }
}
