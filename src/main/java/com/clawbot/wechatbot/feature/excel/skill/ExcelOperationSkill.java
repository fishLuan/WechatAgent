package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Excel 表格操作技能：生成、增删改行、列聚合查询。 */
@Component
public final class ExcelOperationSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "excel-operation";
    private static final Pattern ROW_NUMBER = Pattern.compile(
        "第\\s*(\\d{1,3}|[一二两三四五六七八九十百]{1,4})\\s*行");
    private static final Pattern QUERY = Pattern.compile(
        "(?:查询|计算|看看|统计|找一下)?\\s*(?:表格里)?\\s*(.+?)\\s*的\\s*"
            + "(最大值|最小(?:值)?|合计|总和|平均值|平均数|平均|行数|总数|总行数|多少行)");
    private static final Pattern SUM_PREFIX = Pattern.compile(
        "^(?:合计|统计)\\s*(.+?)(?:的)?(?:金额|总和|合计|数值|值)?$");
    /** 内容分隔标记：只在指令第一行内寻找，且取最靠右的一个，避免把数据行里的冒号/「为」误当分隔符。 */
    private static final Pattern CONTENT_MARKER = Pattern.compile(
        "为|改成|改为|数据(?:是|为)?|内容(?:是|为)?|[:：]");
    private static final Pattern ADD_PREFIX = Pattern.compile(
        "^(?:添加|增加|加入|新增|加)\\s*(?:一行|一条|1行|1条)?\\s*[:：]?\\s*(.+)$");
    private static final Pattern UPDATE_PREFIX = Pattern.compile(
        "^(?:修改|更新|更改|改|把)\\s*(.+)$");
    /** 版本历史指令：版本历史/查看版本/历史版本。 */
    private static final Pattern VERSION_HISTORY_CMD = Pattern.compile(
        "^(?:请|帮我)?\\s*(?:版本历史|查看版本|历史版本)(?:记录|列表)?\\s*$");
    /** 版本历史回复中的时间格式。 */
    private static final DateTimeFormatter VERSION_TIME_FORMAT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ExcelService excelService;

    public ExcelOperationSkill(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public String executorName() {
        return EXECUTOR_NAME;
    }

    @Override
    public SkillResult execute(SkillDefinition definition, SkillRequest request)
        throws Exception {
        if (request == null || request.userId().isBlank()) {
            return SkillResult.failure("Excel skill requires WeChat user context");
        }
        String instruction = request.instruction();
        if (instruction.isBlank()) {
            return SkillResult.failure("Excel skill requires an instruction");
        }
        try {
            return dispatch(request.userId(), instruction);
        } catch (IllegalArgumentException error) {
            return SkillResult.failure(error.getMessage());
        }
    }

    private SkillResult dispatch(String userId, String text) throws Exception {
        ExcelTable table = excelService.loadOrCreate(userId, "表格");

        // 1. 回滚/撤销（放最前：避免「撤销删除第2行」这类说法被当成删除再次执行）
        if (isRollbackCommand(text)) {
            return rollback(userId, table, text);
        }

        // 2. 查询类（直接返回文字，不导出文件）
        SkillResult query = tryQuery(text, table);
        if (query != null) return query;

        // 3. 删除行
        Matcher rowMatcher = ROW_NUMBER.matcher(text);
        if (isAction(text, "删除", "移除", "去掉", "删掉") && rowMatcher.find()) {
            return deleteRow(userId, table, parseRowNumber(rowMatcher.group(1)), text);
        }

        // 4. 修改行
        if (isAction(text, "修改", "更新", "更改", "改") && rowMatcher.find()) {
            return updateRow(userId, table, parseRowNumber(rowMatcher.group(1)), text);
        }

        // 5. 添加行
        Matcher addMatcher = ADD_PREFIX.matcher(text);
        if (addMatcher.matches() && !addMatcher.group(1).isBlank()) {
            return addRow(userId, table, addMatcher.group(1));
        }

        // 6. 生成表格（含表格数据的兜底）
        if (isAction(text, "生成", "创建", "制作", "新建", "做一个") || containsTableData(text)) {
            return createTable(userId, text);
        }

        // 7. 查看版本历史
        if (VERSION_HISTORY_CMD.matcher(text).matches()) {
            return versionHistory(table);
        }

        return SkillResult.failure(
            "无法识别 Excel 操作，支持的指令：生成表格（提供表头和数据）、"
                + "添加一行、修改第N行、删除第N行、查询某列的最大/最小/合计/平均、"
                + "回滚到上一版本、查看版本历史。");
    }

    /** 回滚指令判定：以「撤销/回滚/恢复」开头（可带「请/帮我」前缀）。 */
    private static boolean isRollbackCommand(String text) {
        if (text == null) return false;
        String trimmed = text.trim().replaceFirst("^(?:请|帮我)\\s*", "");
        return trimmed.startsWith("撤销") || trimmed.startsWith("回滚")
            || trimmed.startsWith("恢复");
    }

    /* ========== 各操作实现 ========== */

    private SkillResult createTable(String userId, String text) throws Exception {
        String content = resolveContent(text);
        ExcelService.ParsedTable parsed = ExcelService.parseTableText(content);
        if (parsed.headers().isEmpty()) {
            return SkillResult.failure(
                "没有可用的表格数据，请提供首行为表头、每行一条的表格内容。");
        }
        ExcelTable table = excelService.loadOrCreate(userId, resolveTitle(text));
        // 防静默覆盖：已有非空数据时，指令需显式包含「覆盖」才允许替换
        // 只认第一行，避免数据行里恰好出现「覆盖」二字误触发覆盖。
        if (hasData(table) && !firstLine(text).contains("覆盖")) {
            return SkillResult.failure(
                "❌ 你已经有一张 " + table.getHeaders().size() + "列×"
                    + table.getRows().size() + "行 的表格，直接生成会覆盖原数据，已拦截。"
                    + "确认要替换，请重新发送并在指令中带上「覆盖」二字，例如：\n"
                    + "生成覆盖表格：姓名,城市\n张三,北京\n李四,上海");
        }
        // 覆盖前快照，保留原数据以便回滚
        if (hasData(table)) {
            excelService.snapshotVersion(table, "覆盖生成表格");
        }
        table.setHeaders(parsed.headers());
        table.setRows(parsed.rows());
        excelService.save(table);
        return attachmentResult(
            "✅ 表格已生成（" + parsed.headers().size() + "列×"
                + parsed.rows().size() + "行）：" + table.getTitle(),
            table);
    }

    private SkillResult addRow(String userId, ExcelTable table, String rowData)
        throws Exception {
        requireTable(table);
        List<String> cells = ExcelService.splitRowData(rowData, table);
        if (cells.isEmpty()) {
            return SkillResult.failure("添加的数据行为空。");
        }
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "添加第" + (table.getRows().size() + 1) + "行");
        table.getRows().add(cells);
        excelService.save(table);
        return attachmentResult("✅ 已添加第 " + table.getRows().size() + " 行。", table);
    }

    private SkillResult updateRow(String userId, ExcelTable table, int rowNumber, String text)
        throws Exception {
        requireTable(table);
        int index = rowNumber - 1;
        if (index < 0 || index >= table.getRows().size()) {
            return failureRowRange(table);
        }
        String newData = extractRowData(text);
        if (newData.isBlank()) {
            return SkillResult.failure("缺少新数据，格式示例：修改第2行为 张三,25,北京。");
        }
        List<String> cells = ExcelService.splitRowData(newData, table);
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "修改第" + rowNumber + "行");
        table.getRows().set(index, cells);
        excelService.save(table);
        return attachmentResult("✅ 已修改第 " + rowNumber + " 行。", table);
    }

    private SkillResult deleteRow(String userId, ExcelTable table, int rowNumber, String text)
        throws Exception {
        requireTable(table);
        int index = rowNumber - 1;
        if (index < 0 || index >= table.getRows().size()) {
            return failureRowRange(table);
        }
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "删除第" + rowNumber + "行");
        List<String> removed = table.getRows().remove(index);
        excelService.save(table);
        return attachmentResult(
            "✅ 已删除第 " + rowNumber + " 行（" + String.join("、", removed) + "）。", table);
    }

    private SkillResult tryQuery(String text, ExcelTable table) {
        Matcher query = QUERY.matcher(text);
        if (query.matches()) {
            String column = query.group(1).trim();
            String typeWord = query.group(2);
            if (column.isBlank()) return null;
            return SkillResult.success(
                excelService.queryColumn(table, column, queryType(typeWord)));
        }
        Matcher sum = SUM_PREFIX.matcher(text);
        if (sum.matches() && !sum.group(1).isBlank()) {
            return SkillResult.success(
                excelService.queryColumn(table, sum.group(1).trim(), ExcelService.QueryType.SUM));
        }
        return null;
    }

    /** 回滚到上一版本：先对当前状态快照（「回滚操作」，回滚可撤销），再恢复最新版本。 */
    private SkillResult rollback(String userId, ExcelTable table, String text)
        throws Exception {
        requireTable(table);
        if (excelService.versionCount(table) == 0) {
            return SkillResult.failure(
                "❌ 没有可回滚的版本。做过「添加/修改/删除/覆盖/导入」操作后"
                    + "会生成版本记录，可回滚到最近一次操作前。");
        }
        // 回滚前先对当前状态快照（「回滚操作」），保留回滚前的数据，便于再次撤销
        excelService.snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        boolean restored = excelService.restoreLatestVersion(table);
        if (!restored) {
            return SkillResult.failure("❌ 没有可回滚的版本。");
        }
        excelService.save(table);
        return attachmentResult("✅ 已回滚到上一版本。", table);
    }

    /** 查看版本历史：回复版本数量与最近几次操作说明/时间。 */
    private SkillResult versionHistory(ExcelTable table) {
        long count = excelService.versionCount(table);
        if (count == 0) {
            return SkillResult.success(
                "📜 还没有版本记录。做过「添加/修改/删除/覆盖/导入」操作后即可回滚。");
        }
        List<ExcelTableVersion> recent = excelService.recentVersions(table, 5);
        StringBuilder reply = new StringBuilder(
            "📜 共 " + count + " 条版本记录（每表最多保留 20 条）：");
        for (ExcelTableVersion version : recent) {
            String description = version.getDescription() == null
                ? "未知操作" : version.getDescription();
            reply.append("\n· ").append(description)
                .append("（").append(VERSION_TIME_FORMAT.format(version.getCreatedAt())).append("）");
        }
        if (count > recent.size()) {
            reply.append("\n……共 ").append(count)
                .append(" 条，仅展示最近 ").append(recent.size()).append(" 条");
        }
        return SkillResult.success(reply.toString());
    }

    /* ========== 工具方法 ========== */

    private void requireTable(ExcelTable table) {
        if (table.getHeaders().isEmpty()) {
            throw new IllegalArgumentException("还没有生成表格，请先提供表头和数据生成表格。");
        }
    }

    /** 表格是否已有非空数据（表头和至少一行数据都齐全才视为有数据）。 */
    private boolean hasData(ExcelTable table) {
        return !table.getHeaders().isEmpty() && !table.getRows().isEmpty();
    }

    private SkillResult failureRowRange(ExcelTable table) {
        return SkillResult.failure("行号超出范围，当前共 " + table.getRows().size() + " 行。");
    }

    private SkillResult attachmentResult(String text, ExcelTable table) throws Exception {
        byte[] bytes = excelService.toXlsx(table);
        AgentAttachment attachment = new AgentAttachment(
            AgentAttachment.AttachmentType.FILE,
            bytes,
            "excel-" + System.currentTimeMillis() + ".xlsx",
            "Excel 表格（" + table.getHeaders().size() + "列×"
                + table.getRows().size() + "行）");
        return SkillResult.success(text, List.of(attachment));
    }

    /** 从指令中提取表格数据：优先冒号/换行后的完整内容，否则整个指令作为数据。 */
    private String resolveContent(String text) {
        int contentStart = findContentStart(text);
        return contentStart >= 0 ? text.substring(contentStart) : text;
    }

    /** 提取修改指令中的新数据："为/改成/冒号"之后的内容。 */
    private String extractRowData(String text) {
        int contentStart = findContentStart(text);
        if (contentStart < 0) return "";
        String rowData = firstLine(text).substring(contentStart).trim();
        return rowData.isBlank() ? "" : rowData;
    }

    /** 在第一行内找最靠右的内容分隔标记，返回其后的内容起点（跳过空白）；找不到返回 -1。 */
    private static int findContentStart(String text) {
        String head = firstLine(text);
        Matcher mark = CONTENT_MARKER.matcher(head);
        int contentStart = -1;
        while (mark.find()) {
            contentStart = mark.end();
        }
        if (contentStart < 0) return -1;
        while (contentStart < head.length()
            && Character.isWhitespace(head.charAt(contentStart))) {
            contentStart++;
        }
        return contentStart;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private String resolveTitle(String text) {
        String normalized = text
            .replaceAll("生成|创建|制作|新建|做一个|覆盖|表格|Excel|excel|请|帮我", "")
            .replaceAll("[:：].*$", "")
            .trim();
        if (normalized.isBlank()) return "我的表格";
        return normalized.substring(0, Math.min(30, normalized.length()));
    }

    private boolean isAction(String text, String... actions) {
        for (String action : actions) {
            if (text.contains(action)) return true;
        }
        return false;
    }

    /** 未命中精确动作时，若文本包含"每行一条"形态的表格数据则当作生成。 */
    private boolean containsTableData(String text) {
        long nonBlankLines = text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .count();
        return nonBlankLines >= 2;
    }

    private ExcelService.QueryType queryType(String word) {
        return switch (word) {
            case "最大值" -> ExcelService.QueryType.MAX;
            case "最小", "最小值" -> ExcelService.QueryType.MIN;
            case "合计", "总和", "总数" -> ExcelService.QueryType.SUM;
            case "平均值", "平均数", "平均" -> ExcelService.QueryType.AVERAGE;
            default -> ExcelService.QueryType.COUNT;
        };
    }

    /** 行号解析：支持阿拉伯数字与中文数字（第3行 / 第三行 / 第十五行）。 */
    private static int parseRowNumber(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        String normalized = value.replace('两', '二').replace('〇', '零');
        if (normalized.equals("十")) return 10;
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(normalized.charAt(ten - 1));
            int ones = ten == normalized.length() - 1 ? 0 : digit(normalized.charAt(ten + 1));
            return tens * 10 + ones;
        }
        int result = 0;
        for (int i = 0; i < normalized.length(); i++) {
            result = result * 10 + digit(normalized.charAt(i));
        }
        return result;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }
}
