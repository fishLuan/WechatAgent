package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.service.agent.contract.NewsDataContract;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Excel 表格操作技能：生成、增删改行、列操作、排序、筛选、聚合查询。 */
@Component
public final class ExcelOperationSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "excel-operation";

    // ==================== 正则模式 ====================

    private static final Pattern ROW_NUMBER = Pattern.compile(
        "第\\s*(\\d{1,3}|[一二两三四五六七八九十百]{1,4})\\s*行");
    // 列聚合查询：X列的最大值/最小值/合计/平均值/行数
    private static final Pattern QUERY = Pattern.compile(
        "(?:查询|计算|看看|统计|找一下)?\\s*(?:表格里)?\\s*(.+?)\\s*的\\s*"
            + "(最大值|最小(?:值)?|合计|总和|平均值|平均数|平均|行数|总数|总行数|多少行)");
    private static final Pattern SUM_PREFIX = Pattern.compile(
        "^(?:合计|统计)\\s*(.+?)(?:的)?(?:金额|总和|合计|数值|值)?$");
    // 整表行数：表格有多少行 / 一共几行 / 多少条数据
    private static final Pattern COUNT_ALL = Pattern.compile(
        "^(?:表格|整个表格|我的表格).{0,6}(?:多少行|几行|有几行|总共.{0,3}行|行数|几条|有几条)");
    private static final Pattern COUNT_ALL2 = Pattern.compile(
        "^(?:一共|总共).{0,4}(?:多少行|几行|几条|行数)");
    // 查看整表
    private static final Pattern VIEW_TABLE = Pattern.compile(
        "^(?:查看|看看|显示|浏览|打开|看下|看下|看)(?:一下|下)?(?:表格|我的表格|整个表格)");
    // 查看某行（有行号但无增删改动作）
    private static final Pattern VIEW_ROW = Pattern.compile(
        "^(?:查看|看看|显示)?\\s*第\\s*(\\d{1,3}|[一二两三四五六七八九十百]{1,4})\\s*行(?:是什么|的内容|有啥|有什么)?");
    // 条件筛选
    private static final Pattern FILTER_EQ = Pattern.compile(
        "(?:找出|搜索|筛选|查找|找一下|查一下|哪些|什么)\\s*(.+?)\\s*(?:是|为|等于|=|：|:)\\s*(.+?)(?:的)?(?:行|数据)?$");
    private static final Pattern FILTER_COMPARE = Pattern.compile(
        "(?:找出|搜索|筛选|查找|找一下|查一下)\\s*(.+?)\\s*(>=|<=|!=|大于|>|小于|<|不等于|包含|含有)\\s*(.+?)(?:的)?(?:行|数据)?$");
    // 排序
    private static final Pattern SORT = Pattern.compile(
        "按\\s*(.+?)\\s*(降序|升序|从小到大|从大到小|从低到高|从高到低|倒序|排序|排一下|排一排|排个序)");
    // 列操作
    private static final Pattern ADD_COL = Pattern.compile(
        "(?:添加|增加|加入|新增|加)\\s*(?:一列|列|新列|一个列)\\s*[:：]?\\s*(.+)");
    private static final Pattern DEL_COL = Pattern.compile(
        "(?:删除|移除|去掉|删掉)\\s*(.+?)\\s*(?:这一列|这列|列|那个列)");
    private static final Pattern RENAME_COL = Pattern.compile(
        "(?:把|将)\\s*(.+?)\\s*(?:这一列|这列|列|那一列)\\s*(?:改成|改为|重命名为|更名为)\\s*(-?.+)");
    // 清空表格
    private static final Pattern CLEAR = Pattern.compile(
        "^(?:清空|清除全部|删除全部|删除所有|全部删除|删光|清掉|重置)(?:表格|我的表格|数据|内容)?");
    // 行数据标记
    // DOTALL 让 . 匹配换行，确保冒号后的多行数据能完整提取
    private static final Pattern ROW_DATA_AFTER_MARK = Pattern.compile(
        "(?:为|改成|改为|数据(?:是|为)?|内容(?:是|为)?|[:：])\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern ADD_PREFIX = Pattern.compile(
        "^(?:添加|增加|加入|新增|加)\\s*(?:一行|一条|1行|1条)?\\s*[:：]?\\s*(.+)$");

    private final ExcelService excelService;
    private final ObjectMapper mapper = new ObjectMapper();

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
        if (instruction.isBlank() && !request.dependencyText().isBlank()) {
            instruction = request.dependencyText();
        }
        if (instruction.isBlank()) {
            return SkillResult.failure("Excel skill requires an instruction");
        }
        try {
            JsonNode structuredItems = resolveStructuredItems(request);
            if (isCreateInstruction(instruction)
                && structuredItems != null && !structuredItems.isEmpty()) {
                return createTableFromItems(
                    request.userId(), instruction, structuredItems);
            }
            return dispatch(request.userId(), instruction);
        } catch (IllegalArgumentException error) {
            return SkillResult.failure(error.getMessage());
        }
    }

    /** 剥离 LLM 任务规划器添加的废话前缀/后缀，还原用户原始意图。 */
    private static String normalizeInstruction(String text) {
        if (text == null || text.isBlank()) return text;
        // 1. 去掉尾部标点
        text = text.replaceAll("[。！？!?；;]+$", "");
        // 2. 全角标点转半角
        text = text.replace("，", ",").replace("：", ":").replace("、", ",");
        // 3. 安全填充词：只去掉不可能出现在表格数据/列名中的 LLM 术语
        text = text
            .replace("当前Excel表格中", "")
            .replace("Excel表格中", "")
            .replace("当前表格中", "");
        // 4. 剥离 LLM 前缀
        text = text
            .replaceFirst("^(?:在(?:当前)?(?:的)?Excel表格中|对(?:当前)?(?:的)?表格)\\s*", "")
            .replaceFirst("^(?:获取|查询|进行|执行|完成|操作)\\s*", "")
            .replaceFirst("^(?:请(?:帮我?)?)?\\s*", "")
            .trim();
        return text;
    }

    private SkillResult dispatch(String userId, String text) throws Exception {
        text = normalizeInstruction(text.trim());
        ExcelTable table = excelService.loadOrCreate(userId, "表格");

        // ---- 清空表格 ----
        if (CLEAR.matcher(text).matches()) {
            return clearTable(userId, table);
        }

        // ---- 纯查询类（不导出文件，只返回文字） ----
        SkillResult query = tryQuery(text, table);
        if (query != null) return query;

        // ---- 重命名列（把X改成Y，优先于其他操作） ----
        // 优先匹配含"列"关键字的完整句式
        Matcher renameColGlobal = RENAME_COL.matcher(text);
        if (renameColGlobal.find()) {
            requireTable(table);
            return renameColumn(userId, table,
                renameColGlobal.group(1).trim(), renameColGlobal.group(2).trim());
        }
        // 兜底："把薪资改成月薪"（不含"列"字），排除"把第N行改成"的行操作
        if (text.contains("改成") || text.contains("改为")) {
            Matcher m = Pattern.compile(
                "(?:把|将)\\s*(.{1,10}?)\\s*(?:改成|改为|重命名为|更名为)\\s*(.{1,20})").matcher(text);
            while (m.find()) {
                String oldName = m.group(1).trim();
                String newName = m.group(2).trim();
                if (!oldName.contains("第") && !oldName.contains("行") && !newName.isBlank()) {
                    requireTable(table);
                    return renameColumn(userId, table, oldName, newName);
                }
            }
        }

        // ---- 列操作（有"列"关键字，优先于行操作） ----
        if (text.contains("列")) {
            SkillResult colOp = tryColumnOperation(userId, table, text);
            if (colOp != null) return colOp;
        }

        // ---- 排序 ----
        Matcher sortMatcher = SORT.matcher(text);
        if (sortMatcher.find()) {
            return sortTable(userId, table, sortMatcher.group(1).trim(), sortMatcher.group(2));
        }

        // ---- 行操作 ----
        Matcher rowMatcher = ROW_NUMBER.matcher(text);

        // 删除行
        if (isAction(text, "删除", "移除", "去掉", "删掉") && rowMatcher.find()) {
            return deleteRow(userId, table, parseRowNumber(rowMatcher.group(1)));
        }

        // 修改行
        if (isAction(text, "修改", "更新", "更改", "改") && rowMatcher.reset().find()) {
            return updateRow(userId, table, parseRowNumber(rowMatcher.group(1)), text);
        }

        // 添加行（排除含"列"的指令，避免与列操作混淆）
        if (!text.contains("列")) {
            Matcher addMatcher = ADD_PREFIX.matcher(text);
            if (addMatcher.matches() && !addMatcher.group(1).isBlank()) {
                return addRow(userId, table, addMatcher.group(1));
            }
        }

        // ---- 生成表格 ----
        if (isAction(text, "生成", "创建", "制作", "新建", "做一个") || containsTableData(text)) {
            return createTable(userId, text);
        }

        return SkillResult.failure(
            "无法识别 Excel 操作。支持：生成表格、添加/修改/删除行、添加/删除/重命名列、"
                + "按列排序、按条件筛选、查询最大值/最小值/合计/平均值/行数、清空表格。");
    }

    // ==================== 查询 ====================

    private SkillResult tryQuery(String text, ExcelTable table) {
        // 整表行数
        if ((text.contains("多少行") || text.contains("几行") || text.contains("行数"))
            && (text.contains("表格") || text.contains("一共") || text.contains("总共"))) {
            return SkillResult.success("📊 表格共 " + table.getRows().size() + " 行数据（"
                + table.getHeaders().size() + " 列：" + String.join("、", table.getHeaders()) + "）。");
        }
        // 查看整表：含"查看/看看/显示" + "表格"
        if (text.startsWith("查看") || text.startsWith("看看") || text.startsWith("显示")
            || text.startsWith("浏览") || text.startsWith("打开")) {
            if (text.contains("表格")) {
                return SkillResult.success(formatTableSummary(table));
            }
        }
        // 查看某行：含"第X行"
        Matcher rowView = ROW_NUMBER.matcher(text);
        if (rowView.find() && !isAction(text, "删除", "修改", "改")) {
            requireTable(table);
            int idx = parseRowNumber(rowView.group(1)) - 1;
            if (idx < 0 || idx >= table.getRows().size()) {
                return failureRowRange(table);
            }
            List<String> row = table.getRows().get(idx);
            StringBuilder sb = new StringBuilder("第 " + (idx + 1) + " 行：\n");
            for (int i = 0; i < table.getHeaders().size(); i++) {
                sb.append("  ").append(table.getHeaders().get(i)).append("：")
                    .append(i < row.size() ? row.get(i) : "").append("\n");
            }
            return SkillResult.success(sb.toString().trim());
        }
        // 条件筛选
        SkillResult filter = tryFilter(text, table);
        if (filter != null) return filter;
        // 列聚合查询
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

    // ==================== 条件筛选 ====================

    private SkillResult tryFilter(String text, ExcelTable table) {
        String t = text.trim();
        // FILTER_EQ: 含"找出/筛选/查找/搜索" + "是/为/等于"
        Matcher eq = FILTER_EQ.matcher(t);
        if (eq.matches()) {
            requireTable(table);
            return doFilter(table, eq.group(1).trim(), "等于", eq.group(2).trim());
        }
        // FILTER_COMPARE: 含"找出/筛选/查找/搜索" + 比较运算符
        Matcher cmp = FILTER_COMPARE.matcher(t);
        if (cmp.matches()) {
            requireTable(table);
            return doFilter(table, cmp.group(1).trim(), cmp.group(2).trim(), cmp.group(3).trim());
        }
        return null;
    }

    private SkillResult doFilter(ExcelTable table, String column, String op, String value) {
        int colIdx = findColumnIndex(table, column);
        if (colIdx < 0) {
            return SkillResult.failure("找不到列「" + column + "」，现有列："
                + String.join("、", table.getHeaders()));
        }
        List<List<String>> matched = new ArrayList<>();
        for (List<String> row : table.getRows()) {
            String cell = colIdx < row.size() ? row.get(colIdx) : "";
            if (matchesFilter(cell, op, value)) {
                matched.add(row);
            }
        }
        if (matched.isEmpty()) {
            return SkillResult.success("🔍 没有找到「" + column + "」" + op + "「" + value + "」的行。");
        }
        StringBuilder sb = new StringBuilder("🔍 找到 " + matched.size() + " 条匹配的行：\n");
        for (int i = 0; i < Math.min(matched.size(), 20); i++) {
            List<String> row = matched.get(i);
            sb.append("  ");
            for (int j = 0; j < table.getHeaders().size(); j++) {
                if (j > 0) sb.append(" | ");
                sb.append(table.getHeaders().get(j)).append("：")
                    .append(j < row.size() ? row.get(j) : "");
            }
            sb.append("\n");
        }
        if (matched.size() > 20) {
            sb.append("  … 还有 " + (matched.size() - 20) + " 条，共 " + matched.size() + " 条。");
        }
        return SkillResult.success(sb.toString().trim());
    }

    private boolean matchesFilter(String cell, String op, String value) {
        return switch (op) {
            case "等于", "是", "为", "=" -> cell.trim().equals(value.trim());
            case "不等于", "!=" -> !cell.trim().equals(value.trim());
            case "大于", ">" -> parseDouble(cell) > parseDouble(value);
            case "小于", "<" -> parseDouble(cell) < parseDouble(value);
            case ">=" -> parseDouble(cell) >= parseDouble(value);
            case "<=" -> parseDouble(cell) <= parseDouble(value);
            case "包含", "含有" -> cell.contains(value);
            default -> cell.trim().equals(value.trim());
        };
    }

    // ==================== 排序 ====================

    private SkillResult sortTable(String userId, ExcelTable table, String column, String orderWord)
        throws Exception {
        requireTable(table);
        int colIdx = findColumnIndex(table, column);
        if (colIdx < 0) {
            return SkillResult.failure("找不到列「" + column + "」，现有列："
                + String.join("、", table.getHeaders()));
        }
        // 升序判定优先于降序（避免"从小到大"中的"大"、"从低到高"中的"高"被误判）
        boolean asc = orderWord.contains("升") || orderWord.contains("小") || orderWord.contains("低");
        boolean desc = !asc && (orderWord.contains("降") || orderWord.contains("大")
            || orderWord.contains("高") || orderWord.contains("倒"));
        Comparator<List<String>> cmp = (a, b) -> {
            String va = colIdx < a.size() ? a.get(colIdx) : "";
            String vb = colIdx < b.size() ? b.get(colIdx) : "";
            // 尝试数值比较，否则字符串比较
            try {
                return Double.compare(Double.parseDouble(va), Double.parseDouble(vb));
            } catch (NumberFormatException ignored) {
                return va.compareTo(vb);
            }
        };
        if (desc) cmp = cmp.reversed();
        table.getRows().sort(cmp);
        excelService.save(table);
        String dir = desc ? "降序" : "升序";
        return attachmentResult("✅ 已按「" + column + "」" + dir + "排列。", table);
    }

    // ==================== 列操作 ====================

    private SkillResult tryColumnOperation(String userId, ExcelTable table, String text)
        throws Exception {
        requireTable(table);
        // 重命名列：把X这一列改成Y
        Matcher renameCol = RENAME_COL.matcher(text);
        if (renameCol.find()) {
            return renameColumn(userId, table, renameCol.group(1).trim(), renameCol.group(2).trim());
        }
        // 添加列：先尝试从引号提取列名（处理 LLM 改写），再走正则
        String addName = extractQuotedName(text);
        if (addName != null && isAddAction(text)) {
            return addColumn(userId, table, addName);
        }
        Matcher addCol = ADD_COL.matcher(text);
        if (addCol.matches() && !addCol.group(1).isBlank()) {
            return addColumn(userId, table, addCol.group(1).trim());
        }
        // 删除列：同样先引号后正则
        String delName = extractQuotedName(text);
        if (delName != null && isAction(text, "删除", "移除", "去掉", "删掉")) {
            return deleteColumn(userId, table, delName);
        }
        Matcher delCol = DEL_COL.matcher(text);
        if (delCol.find()) {
            return deleteColumn(userId, table, delCol.group(1).trim());
        }
        return null;
    }

    private static boolean isAddAction(String text) {
        return isAction(text, "添加", "增加", "新增", "加入", "加");
    }

    /** 从文本中提取引号内的列名（如「入职日期」、"城市"）。 */
    private static String extractQuotedName(String text) {
        // 只匹配开引号
        String[] openers = {"「", "\"", "'", "《", "【"};
        String[] closers = {"」", "\"", "'", "》", "】"};
        for (int i = 0; i < openers.length; i++) {
            int start = text.indexOf(openers[i]);
            if (start < 0) continue;
            int end = text.indexOf(closers[i], start + 1);
            if (end > start) return text.substring(start + 1, end).trim();
        }
        return null;
    }

    private SkillResult addColumn(String userId, ExcelTable table, String colName)
        throws Exception {
        if (colName.isBlank()) return SkillResult.failure("列名不能为空。");
        if (table.getHeaders().contains(colName)) {
            return SkillResult.failure("已存在列「" + colName + "」，请换一个名字。");
        }
        table.getHeaders().add(colName);
        for (List<String> row : table.getRows()) {
            row.add(""); // 新列填空白
        }
        excelService.save(table);
        return attachmentResult("✅ 已添加列「" + colName + "」（第 " + table.getHeaders().size() + " 列）。", table);
    }

    private SkillResult deleteColumn(String userId, ExcelTable table, String colName)
        throws Exception {
        int colIdx = findColumnIndex(table, colName);
        if (colIdx < 0) {
            return SkillResult.failure("找不到列「" + colName + "」，现有列："
                + String.join("、", table.getHeaders()));
        }
        String removed = table.getHeaders().remove(colIdx);
        for (List<String> row : table.getRows()) {
            if (colIdx < row.size()) row.remove(colIdx);
        }
        excelService.save(table);
        return attachmentResult("✅ 已删除列「" + removed + "」。", table);
    }

    private SkillResult renameColumn(String userId, ExcelTable table, String oldName, String newName)
        throws Exception {
        int colIdx = findColumnIndex(table, oldName);
        if (colIdx < 0) {
            return SkillResult.failure("找不到列「" + oldName + "」，现有列："
                + String.join("、", table.getHeaders()));
        }
        if (newName.isBlank()) return SkillResult.failure("新列名不能为空。");
        table.getHeaders().set(colIdx, newName);
        excelService.save(table);
        return attachmentResult("✅ 已将「" + oldName + "」重命名为「" + newName + "」。", table);
    }

    // ==================== 清空表格 ====================

    private SkillResult clearTable(String userId, ExcelTable table) throws Exception {
        int removed = table.getRows().size();
        table.setRows(new ArrayList<>());
        excelService.save(table);
        return attachmentResult("✅ 已清空表格（删除了 " + removed + " 行数据），表头保留。", table);
    }

    // ==================== 行操作实现 ====================

    private SkillResult createTable(String userId, String text) throws Exception {
        String content = resolveContent(text);
        // 如果第一行不含分隔符（如"生成表格 团队成员信息"），跳过它
        content = skipNonTableLeadingLine(content);
        ExcelService.ParsedTable parsed = ExcelService.parseTableText(content);
        if (parsed.headers().isEmpty()) {
            return SkillResult.failure(
                "没有可用的表格数据，请提供首行为表头、每行一条的表格内容。");
        }
        ExcelTable table = excelService.loadOrCreate(userId, resolveTitle(text));
        table.setTitle(resolveTitle(text));
        table.setHeaders(parsed.headers());
        table.setRows(parsed.rows());
        excelService.save(table);
        return attachmentResult(
            "✅ 表格已生成（" + parsed.headers().size() + "列×"
                + parsed.rows().size() + "行）：" + table.getTitle(),
            table);
    }

    private SkillResult createTableFromItems(
        String userId, String instruction, JsonNode items
    ) throws Exception {
        boolean newsItems = items.path(0).isObject()
            && items.path(0).has("title")
            && (items.path(0).has("description") || items.path(0).has("source"));
        List<String> fields = newsItems
            ? NewsDataContract.ITEM_FIELDS
            : collectFields(items);
        if (fields.isEmpty()) {
            return SkillResult.failure("前置任务没有提供可转换为表格的结构化字段。");
        }
        List<String> headers = fields.stream()
            .map(this::displayHeader)
            .toList();
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode item : items) {
            List<String> row = new ArrayList<>();
            for (String field : fields) {
                JsonNode value = item.isObject() ? item.path(field) : item;
                row.add(cellText(value));
            }
            rows.add(row);
        }
        String title = newsItems ? "新闻" : resolveTitle(instruction);
        ExcelTable table = excelService.loadOrCreate(userId, title);
        table.setTitle(title);
        table.setHeaders(headers);
        table.setRows(rows);
        excelService.save(table);
        return attachmentResult(
            "✅ 表格已生成（" + headers.size() + "列×" + rows.size()
                + "行）：" + title,
            table);
    }

    private JsonNode resolveStructuredItems(SkillRequest request) {
        JsonNode fromInput = findItems(request.resolvedInput(), 0);
        if (fromInput != null) return fromInput;
        String dependency = request.dependencyText();
        if (dependency.isBlank()) return null;
        try {
            JsonNode parsed = mapper.readTree(dependency);
            JsonNode found = findItems(parsed, 0);
            if (found != null) return found;
        } catch (Exception ignored) {
            // Dependency labels may surround the JSON; extract the embedded object below.
        }
        int start = dependency.indexOf('{');
        int end = dependency.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return findItems(mapper.readTree(dependency.substring(start, end + 1)), 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode findItems(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 4) {
            return null;
        }
        if (node.isArray()) return node;
        for (String field : List.of("weather_info", "route_info")) {
            JsonNode structuredObject = node.path(field);
            if (structuredObject.isObject() && !structuredObject.isEmpty()) {
                return mapper.createArrayNode().add(structuredObject.deepCopy());
            }
        }
        for (String field : List.of(
            NewsDataContract.ITEMS, "news_list", "news", "articles", "results", "value")) {
            JsonNode child = node.path(field);
            if (child.isArray()) return child;
            if (child.isObject()) {
                JsonNode nested = findItems(child, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private List<String> collectFields(JsonNode items) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (JsonNode item : items) {
            if (!item.isObject()) return List.of("value");
            item.fieldNames().forEachRemaining(fields::add);
        }
        return List.copyOf(fields);
    }

    private String displayHeader(String field) {
        String newsHeader = NewsDataContract.DISPLAY_HEADERS.get(field);
        if (newsHeader != null) return newsHeader;
        return switch (field) {
            case "city" -> "城市";
            case "date" -> "日期";
            case "weather" -> "天气";
            case "temperature" -> "气温";
            case "humidity" -> "湿度";
            case "wind_direction" -> "风向";
            case "wind_power" -> "风力";
            case "report_time" -> "发布时间";
            case "origin" -> "起点";
            case "destination" -> "终点";
            case "strategy" -> "出行方式";
            case "total_distance_km" -> "总距离（公里）";
            case "total_duration_minutes" -> "总时长（分钟）";
            default -> field;
        };
    }

    private String cellText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private boolean isCreateInstruction(String text) {
        return isAction(text, "生成", "创建", "制作", "新建", "做一个", "导出");
    }

    /** 跳过不含分隔符的标题行，保留真正的表头和数据。 */
    private static String skipNonTableLeadingLine(String content) {
        if (content == null || content.isBlank()) return content;
        // 取第一行（到第一个换行符为止）
        int newline = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') { newline = i; break; }
        }
        if (newline <= 0) return content; // 只有一行，不处理
        String firstLine = content.substring(0, newline);
        // 检查第一行是否含分隔符
        boolean hasDelim = firstLine.contains(",") || firstLine.contains("\t")
            || firstLine.contains("|") || firstLine.contains(";") || firstLine.contains("，");
        if (hasDelim) return content; // 第一行是表头，保留
        // 跳过第一行和接下来的空白
        int start = newline;
        while (start < content.length() && (content.charAt(start) == '\n' || content.charAt(start) == '\r')) {
            start++;
        }
        if (start >= content.length()) return content;
        return content.substring(start);
    }

    private SkillResult addRow(String userId, ExcelTable table, String rowData)
        throws Exception {
        requireTable(table);
        List<String> cells = ExcelService.splitRowData(rowData, table);
        if (cells.isEmpty()) {
            return SkillResult.failure("添加的数据行为空。");
        }
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
        table.getRows().set(index, cells);
        excelService.save(table);
        return attachmentResult("✅ 已修改第 " + rowNumber + " 行。", table);
    }

    private SkillResult deleteRow(String userId, ExcelTable table, int rowNumber)
        throws Exception {
        requireTable(table);
        int index = rowNumber - 1;
        if (index < 0 || index >= table.getRows().size()) {
            return failureRowRange(table);
        }
        List<String> removed = table.getRows().remove(index);
        excelService.save(table);
        return attachmentResult(
            "✅ 已删除第 " + rowNumber + " 行（" + String.join("、", removed) + "）。", table);
    }

    // ==================== 工具方法 ====================

    private void requireTable(ExcelTable table) {
        if (table.getHeaders().isEmpty()) {
            throw new IllegalArgumentException("还没有生成表格，请先提供表头和数据生成表格。");
        }
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
        Matcher mark = ROW_DATA_AFTER_MARK.matcher(text);
        if (mark.find() && !mark.group(1).isBlank()) {
            return mark.group(1);
        }
        return text;
    }

    private String extractRowData(String text) {
        Matcher mark = ROW_DATA_AFTER_MARK.matcher(text);
        if (mark.find()) {
            return mark.group(1).trim();
        }
        return "";
    }

    private String resolveTitle(String text) {
        String normalized = text
            .replaceAll("生成|创建|制作|新建|做一个|表格|Excel|excel|请|帮我", "")
            .replaceAll("[:：].*$", "")
            .trim();
        if (normalized.isBlank()) return "我的表格";
        return normalized.substring(0, Math.min(30, normalized.length()));
    }

    private static boolean isAction(String text, String... actions) {
        for (String action : actions) {
            if (text.contains(action)) return true;
        }
        return false;
    }

    /** 未命中精确动作时，需同时满足"多行"且"含分隔符"才当作表格数据，避免误覆盖已有表格。 */
    private boolean containsTableData(String text) {
        List<String> lines = text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .collect(Collectors.toList());
        if (lines.size() < 2) return false;
        for (String line : lines) {
            if (line.contains(",") || line.contains("\t") || line.contains("|")
                || line.contains(";") || line.contains("，")) {
                return true;
            }
        }
        return false;
    }

    /** 格式化整表概览。 */
    private String formatTableSummary(ExcelTable table) {
        if (table.getHeaders().isEmpty()) {
            return "📊 表格「" + table.getTitle() + "」还没有数据，请先生成表头和数据。";
        }
        StringBuilder sb = new StringBuilder("📊 表格「" + table.getTitle() + "」："
            + table.getHeaders().size() + "列×" + table.getRows().size() + "行\n");
        sb.append("表头：").append(String.join(" | ", table.getHeaders())).append("\n");
        int show = Math.min(table.getRows().size(), 5);
        if (show > 0) {
            sb.append("数据（前 " + show + " 行）：\n");
            for (int i = 0; i < show; i++) {
                sb.append("  ").append(i + 1).append(". ");
                List<String> row = table.getRows().get(i);
                for (int j = 0; j < table.getHeaders().size(); j++) {
                    if (j > 0) sb.append(" | ");
                    sb.append(table.getHeaders().get(j)).append("：").append(j < row.size() ? row.get(j) : "");
                }
                sb.append("\n");
            }
            if (table.getRows().size() > show) {
                sb.append("  … 还有 " + (table.getRows().size() - show) + " 行。");
            }
        }
        return sb.toString().trim();
    }

    /** 模糊查找列下标：精确匹配优先，其次包含匹配。 */
    private int findColumnIndex(ExcelTable table, String columnName) {
        List<String> headers = table.getHeaders();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equals(columnName)) return i;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).contains(columnName) || columnName.contains(headers.get(i))) return i;
        }
        return -1;
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

    private double parseDouble(String text) {
        if (text == null || text.isBlank()) return 0;
        try {
            String cleaned = text.replace(",", "").replace("，", "")
                .replace("￥", "").replace("¥", "").replace("%", "").trim();
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

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
