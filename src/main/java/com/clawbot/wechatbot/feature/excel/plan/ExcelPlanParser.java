package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户文本解析成结构化 ExcelPlan。
 * 路由逻辑自重构前的 ExcelOperationSkill.dispatch 整体搬入，行为完全一致：
 * 回滚判定优先、查询/删除/修改/添加/生成/版本历史顺序、覆盖保护标记、内容提取规则。
 * 另新增复合任务路由：带切分点的多条分析指令（如「删除重复订单，补全空白地区」）先于单操作切分为
 * 线性依赖链；不含切分点的单操作文本行为不变。
 * 解析器只产出计划，不执行任何修改。
 */
public final class ExcelPlanParser {

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
    /** 版本历史指令：版本历史/查看版本/历史版本/查看版本历史，前缀「请/帮我」可任意组合与重复。 */
    private static final Pattern VERSION_HISTORY_CMD = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:版本历史|历史版本|查看版本(?:历史)?)(?:记录|列表)?\\s*$");
    /** 排序指令：按X排序/按X升序·降序/按X从小到大·从大到小/把表格按X倒序。 */
    private static final Pattern SORT_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*(?:把表格)?\\s*按\\s*(.+?)\\s*"
            + "(?:排序|升序|正序|降序|倒序|从小到大|从大到小)(?:排序)?\\s*$");
    /** 去重指令（整行）：去重 / 删除重复行·订单·数据。 */
    private static final Pattern DEDUPLICATE_PLAIN = Pattern.compile(
        "^(?:请|帮我\\s*)*去重\\s*$");
    private static final Pattern DEDUPLICATE_ACTION = Pattern.compile(
        "^(?:请|帮我\\s*)*(?:删除|去掉|移除|清除)\\s*重复\\s*(?:行|数据|记录|订单)?\\s*$");
    /** 去重指令（按列）：按X列去重 / 按X去重。 */
    private static final Pattern DEDUPLICATE_COLUMN = Pattern.compile(
        "^(?:请|帮我\\s*)*按\\s*(.+?)\\s*(?:列)?\\s*去重\\s*$");
    /** 分组汇总指令：按X汇总Y / 按X统计Y的合计·求和·平均·最大·最小·数量，可带「占比|百分比」。 */
    private static final Pattern GROUP_SUMMARY_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*按\\s*(.+?)\\s*(?:汇总|统计)\\s*(.+?)\\s*(?:的)?\\s*"
            + "(合计|求和|总和|平均|平均值|平均数|最大|最小|数量|个数|行数)?"
            + "(?:并|且)?\\s*(?:算|计算)?\\s*(?:占比|百分比)?\\s*$");
    /** 缺失补全指令：补全X列/补全空白X列（默认「未知」），或 把X列补全为V（指定值）。 */
    private static final Pattern FILL_MISSING_PLAIN = Pattern.compile(
        "^(?:请|帮我\\s*)*补全\\s*(?:空白|空的)?\\s*(.+?)\\s*(?:列)?\\s*$");
    private static final Pattern FILL_MISSING_VALUE = Pattern.compile(
        "^(?:请|帮我\\s*)*把\\s*(.+?)\\s*(?:列)?\\s*补全为\\s*(.+?)\\s*$");
    /** 复合任务切分点：标点分隔符（中文逗号/顿号/分号、半角逗号/分号）或连接词（然后/接着/同时/并且/并/再）。 */
    private static final Pattern SPLIT_SEPARATOR = Pattern.compile(
        "[，、；,;]|然后|接着|同时|并且|并|再");
    /** 复合任务切分判定：分隔符后紧跟分析类操作开头词（按/去重/删除重复/去掉重复/移除重复/清除重复/补全/把/统计/汇总/排序）才切。 */
    private static final Pattern ANALYSIS_START_WORD = Pattern.compile(
        "^(?:按|去重|删除重复|去掉重复|移除重复|清除重复|补全|把|统计|汇总|排序)");
    /** 知识管理指令：添加知识（冒号后可带类别词与内容）/ 查看知识(库) / 删除知识 X。 */
    private static final Pattern KNOWLEDGE_ADD_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*添加知识\\s*[:：]?\\s*(.+)$");
    private static final Pattern KNOWLEDGE_LIST_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*查看知识(?:库)?\\s*$");
    private static final Pattern KNOWLEDGE_DELETE_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*删除知识\\s*(.+?)\\s*$");
    /** 片段尾部残留分隔符（切分点前的标点/连接词），清理时按长度从长到短尝试。 */
    private static final List<String> SEPARATOR_TAIL_WORDS = List.of(
        "然后", "接着", "同时", "并且", "并", "再", "，", "、", "；", ",", ";");

    /** 解析用户文本为 ExcelPlan；无法识别时返回 null（由调用方给出兜底提示）。 */
    public ExcelPlan parse(String userId, String text) {
        // 1. 回滚/撤销（放最前：避免「撤销删除第2行」这类说法被当成删除再次执行）
        if (isRollbackCommand(text)) {
            return plan(userId, op("1", ExcelOperationType.ROLLBACK, Map.of()));
        }

        // 2. 查询类（直接返回文字，不导出文件）
        ExcelOperation query = tryQuery(text);
        if (query != null) return plan(userId, query);

        // 复合任务（多条分析指令串联，如「删除重复订单，补全空白地区」）：先于单操作尝试切分。
        // 单操作文本不含切分点，仍原样命中单操作（回归不变）；而带切分点的整段文本即便被单操作
        // 正则懒匹配命中，列名也必然被尾部指令污染，复合切分才是用户本意。
        ExcelPlan composite = tryComposite(userId, text);
        if (composite != null) return composite;

        // 分析类操作（排序/去重/分组汇总/缺失补全）：放在查询之后、删除行之前
        ExcelOperation analysis = tryAnalysis(text);
        if (analysis != null) return plan(userId, analysis);

        // 知识管理指令（必须在「添加行」之前判定：「添加知识：…」会命中添加行前缀正则，避免被当成加行）
        ExcelPlan knowledge = tryKnowledge(userId, text);
        if (knowledge != null) return knowledge;

        // 3. 删除行
        Matcher rowMatcher = ROW_NUMBER.matcher(text);
        if (isAction(text, "删除", "移除", "去掉", "删掉") && rowMatcher.find()) {
            return plan(userId, op("1", ExcelOperationType.DELETE_ROW,
                Map.of("rowNumber", String.valueOf(parseRowNumber(rowMatcher.group(1))))));
        }

        // 4. 修改行
        if (isAction(text, "修改", "更新", "更改", "改") && rowMatcher.find()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("rowNumber", String.valueOf(parseRowNumber(rowMatcher.group(1))));
            params.put("cells", extractRowData(text));
            return plan(userId, op("1", ExcelOperationType.UPDATE_ROW, params));
        }

        // 5. 添加行
        Matcher addMatcher = ADD_PREFIX.matcher(text);
        if (addMatcher.matches() && !addMatcher.group(1).isBlank()) {
            return plan(userId, op("1", ExcelOperationType.ADD_ROW,
                Map.of("cells", addMatcher.group(1))));
        }

        // 6. 生成表格（含表格数据的兜底）
        if (isAction(text, "生成", "创建", "制作", "新建", "做一个") || containsTableData(text)) {
            return plan(userId, op("1", ExcelOperationType.CREATE_TABLE, createTableParams(text)));
        }

        // 7. 查看版本历史
        if (VERSION_HISTORY_CMD.matcher(text).matches()) {
            return plan(userId, op("1", ExcelOperationType.VERSION_HISTORY, Map.of()));
        }

        return null;
    }

    private static ExcelPlan plan(String userId, ExcelOperation... operations) {
        return new ExcelPlan(userId, List.of(operations));
    }

    private static ExcelOperation op(String id, ExcelOperationType type, Map<String, String> params) {
        return new ExcelOperation(id, type, params, List.of());
    }

    /** 查询路由：命中「某列的聚合」或「合计/统计 前缀」时产出 QUERY 操作，否则返回 null。 */
    private ExcelOperation tryQuery(String text) {
        // 「按X统计Y的合计/平均/行数」这类说法会被下方正则误当成列查询（列名带「按X统计」前缀），
        // 让位给分析分支的分组汇总（GROUP_SUMMARY_CMD 是同一份正则，判定结果一致）
        if (isGroupSummaryCommand(text)) {
            return null;
        }
        Matcher query = QUERY.matcher(text);
        if (query.matches()) {
            String column = query.group(1).trim();
            String typeWord = query.group(2);
            if (column.isBlank()) return null;
            return op("1", ExcelOperationType.QUERY, Map.of(
                "column", column, "queryType", queryType(typeWord).name()));
        }
        Matcher sum = SUM_PREFIX.matcher(text);
        if (sum.matches() && !sum.group(1).isBlank()) {
            return op("1", ExcelOperationType.QUERY, Map.of(
                "column", sum.group(1).trim(), "queryType", ExcelService.QueryType.SUM.name()));
        }
        return null;
    }

    /** 分析类操作路由：排序 → 去重 → 分组汇总 → 缺失补全（关键词互不重叠，顺序固定）。 */
    private ExcelOperation tryAnalysis(String text) {
        ExcelOperation sort = trySort(text);
        if (sort != null) return sort;
        ExcelOperation deduplicate = tryDeduplicate(text);
        if (deduplicate != null) return deduplicate;
        ExcelOperation groupSummary = tryGroupSummary(text);
        if (groupSummary != null) return groupSummary;
        return tryFillMissing(text);
    }

    /** 排序路由：direction 默认 ASC；出现「降序|倒序|从大到小」→ DESC。 */
    private ExcelOperation trySort(String text) {
        Matcher matcher = SORT_CMD.matcher(text);
        if (!matcher.matches()) return null;
        String column = matcher.group(1).trim();
        if (column.isBlank()) return null;
        boolean descending = text.contains("降序") || text.contains("倒序")
            || text.contains("从大到小");
        return op("1", ExcelOperationType.SORT,
            Map.of("column", column, "direction", descending ? "DESC" : "ASC"));
    }

    /** 去重路由：按X去重产出指定列；去重/删除重复行·订单·数据按整行去重。 */
    private ExcelOperation tryDeduplicate(String text) {
        Matcher byColumn = DEDUPLICATE_COLUMN.matcher(text);
        if (byColumn.matches()) {
            String column = byColumn.group(1).trim();
            if (!column.isBlank()) {
                return op("1", ExcelOperationType.DEDUPLICATE, Map.of("column", column));
            }
        }
        if (DEDUPLICATE_PLAIN.matcher(text).matches()
            || DEDUPLICATE_ACTION.matcher(text).matches()) {
            return op("1", ExcelOperationType.DEDUPLICATE, Map.of());
        }
        return null;
    }

    /**
     * 复合任务路由：先于单操作尝试，按「分隔符后紧跟分析类操作开头词」安全切分，
     * 每个片段走既有分析分支（排序/去重/分组汇总/缺失补全），产出线性依赖链（第 i 步依赖第 i-1 步）。
     * 单操作文本不含切分点，切分不出 ≥2 段时返回 null 交给单操作路由（回归不变）。
     * 任一条件不满足（含换行的多行文本、任一片段解析不出分析操作、片段数 < 2）→ 返回 null 退回原路由。
     */
    private ExcelPlan tryComposite(String userId, String text) {
        // 多行是表格数据形态，绝不切分
        if (text.contains("\n")) {
            return null;
        }
        List<String> fragments = splitFragments(text);
        if (fragments.size() < 2) {
            return null;
        }
        List<ExcelOperation> operations = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            ExcelOperation fragmentOp = tryAnalysis(fragments.get(i));
            // 任一片段解析不出分析操作（如含「添加」「生成」）→ 整个复合让位，退回原单操作流程
            if (fragmentOp == null) {
                return null;
            }
            // 线性依赖链：第 i 步依赖前一步（id 按片段顺序 1、2、3…）
            operations.add(new ExcelOperation(String.valueOf(i + 1), fragmentOp.type(),
                fragmentOp.params(), i == 0 ? List.of() : List.of(String.valueOf(i))));
        }
        return plan(userId, operations.toArray(new ExcelOperation[0]));
    }

    /** 按切分点切分文本：分隔符后紧跟操作开头词才切（切在分隔符末尾，残留分隔符由 cleanFragment 清理）。 */
    private static List<String> splitFragments(String text) {
        List<String> fragments = new ArrayList<>();
        Matcher matcher = SPLIT_SEPARATOR.matcher(text);
        int fragmentStart = 0;
        while (matcher.find()) {
            String rest = text.substring(matcher.end()).stripLeading();
            if (ANALYSIS_START_WORD.matcher(rest).find()) {
                fragments.add(cleanFragment(text.substring(fragmentStart, matcher.end())));
                fragmentStart = matcher.end();
            }
        }
        fragments.add(cleanFragment(text.substring(fragmentStart)));
        return fragments;
    }

    /**
     * 知识管理路由：
     * 添加知识 → KNOWLEDGE_ADD（category 映射为四类枚举名，非法类别保留原文交给校验器提示；
     * content 为「触发词→内容」或「触发词=内容」，由 Handler 解析）；
     * 查看知识(库) → KNOWLEDGE_LIST；删除知识 X → KNOWLEDGE_DELETE。
     */
    private ExcelPlan tryKnowledge(String userId, String text) {
        Matcher add = KNOWLEDGE_ADD_CMD.matcher(text);
        if (add.matches()) {
            String body = add.group(1).trim();
            int split = firstWhitespaceIndex(body);
            String categoryWord = split < 0 ? body : body.substring(0, split);
            String content = split < 0 ? "" : body.substring(split + 1).trim();
            return plan(userId, op("1", ExcelOperationType.KNOWLEDGE_ADD,
                Map.of("category", knowledgeCategory(categoryWord), "content", content)));
        }
        if (KNOWLEDGE_LIST_CMD.matcher(text).matches()) {
            return plan(userId, op("1", ExcelOperationType.KNOWLEDGE_LIST, Map.of()));
        }
        Matcher delete = KNOWLEDGE_DELETE_CMD.matcher(text);
        if (delete.matches()) {
            return plan(userId, op("1", ExcelOperationType.KNOWLEDGE_DELETE,
                Map.of("keyword", delete.group(1).trim())));
        }
        return null;
    }

    /** 知识类别词 → 枚举名；无法识别的类别保留原文，由校验器给出明确提示。 */
    private static String knowledgeCategory(String word) {
        return switch (word) {
            case "字段映射" -> ExcelRagKnowledge.CATEGORY_FIELD_MAPPING;
            case "业务规则" -> ExcelRagKnowledge.CATEGORY_BUSINESS_RULE;
            case "操作示例" -> ExcelRagKnowledge.CATEGORY_OPERATION_EXAMPLE;
            case "模板" -> ExcelRagKnowledge.CATEGORY_TEMPLATE;
            default -> word;
        };
    }

    /** 首个空白字符下标；无空白返回 -1。 */
    private static int firstWhitespaceIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 清理切出的片段：去除首尾空白与尾部残留分隔符（标点/连接词可连续出现）。 */
    private static String cleanFragment(String fragment) {
        String cleaned = fragment.strip();
        boolean stripped;
        do {
            stripped = false;
            for (String separator : SEPARATOR_TAIL_WORDS) {
                if (cleaned.endsWith(separator)) {
                    cleaned = cleaned.substring(0, cleaned.length() - separator.length()).strip();
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        return cleaned;
    }

    /** 分组汇总路由：aggregate 默认 SUM；含「占比|百分比」时标记 includeRatio=true。 */
    private ExcelOperation tryGroupSummary(String text) {
        Matcher matcher = GROUP_SUMMARY_CMD.matcher(text);
        if (!matcher.matches()) return null;
        String groupColumn = matcher.group(1).trim();
        String valueColumn = matcher.group(2).trim();
        if (groupColumn.isBlank()) return null;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("groupColumn", groupColumn);
        String aggregateWord = matcher.group(3);
        if (aggregateWord == null && "行数".equals(valueColumn)) {
            // 「按X统计行数」这类说法没有数值列，视为按组计数
            params.put("aggregate", ExcelService.QueryType.COUNT.name());
        } else {
            if (!valueColumn.isBlank()) {
                params.put("valueColumn", valueColumn);
            }
            params.put("aggregate", aggregate(aggregateWord));
        }
        if (text.contains("占比") || text.contains("百分比")) {
            params.put("includeRatio", "true");
        }
        return op("1", ExcelOperationType.GROUP_SUMMARY, params);
    }

    /** 聚合词映射：合计/求和/总和→SUM，平均→AVERAGE，最大→MAX，最小→MIN，数量/个数/行数→COUNT，缺省→SUM。 */
    private static String aggregate(String word) {
        if (word == null) return ExcelService.QueryType.SUM.name();
        return switch (word) {
            case "合计", "求和", "总和" -> ExcelService.QueryType.SUM.name();
            case "平均", "平均值", "平均数" -> ExcelService.QueryType.AVERAGE.name();
            case "最大" -> ExcelService.QueryType.MAX.name();
            case "最小" -> ExcelService.QueryType.MIN.name();
            default -> ExcelService.QueryType.COUNT.name(); // 数量/个数/行数
        };
    }

    /** 缺失补全路由：无指定值时默认「未知」；「把X补全为V」取指定值。 */
    private ExcelOperation tryFillMissing(String text) {
        Matcher withValue = FILL_MISSING_VALUE.matcher(text);
        if (withValue.matches()) {
            String column = withValue.group(1).trim();
            String value = withValue.group(2).trim();
            if (!column.isBlank() && !value.isBlank()) {
                return op("1", ExcelOperationType.FILL_MISSING,
                    Map.of("column", column, "value", value));
            }
        }
        Matcher plain = FILL_MISSING_PLAIN.matcher(text);
        if (plain.matches()) {
            String column = plain.group(1).trim();
            if (!column.isBlank()) {
                return op("1", ExcelOperationType.FILL_MISSING,
                    Map.of("column", column, "value", "未知"));
            }
        }
        return null;
    }

    /** 分组汇总指令判定（与查询分支共用同一份正则，供 tryQuery 让路）。 */
    private static boolean isGroupSummaryCommand(String text) {
        return GROUP_SUMMARY_CMD.matcher(text).matches();
    }

    /** 生成表格参数：表头行/数据行/是否显式带「覆盖」/标题（标题用于 loadOrCreate）。 */
    private static Map<String, String> createTableParams(String text) {
        String content = resolveContent(text);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("headers", firstLine(content));
        int newline = content.indexOf('\n');
        params.put("rows", newline < 0 ? "" : content.substring(newline + 1));
        // 覆盖保护只认第一行，避免数据行里恰好出现「覆盖」二字误触发覆盖
        params.put("overwrite", firstLine(text).contains("覆盖") ? "true" : "false");
        params.put("title", resolveTitle(text));
        return params;
    }

    /** 回滚指令判定：以「撤销/回滚/恢复」开头（可带「请/帮我」前缀）。 */
    private static boolean isRollbackCommand(String text) {
        if (text == null) return false;
        // 前缀可重复组合（"请帮我回滚"剥掉"请帮我"两层）
        String trimmed = text.trim().replaceFirst("^(?:(?:请|帮我)\\s*)*", "");
        return trimmed.startsWith("撤销") || trimmed.startsWith("回滚")
            || trimmed.startsWith("恢复");
    }

    /** 从指令中提取表格数据：优先冒号/换行后的完整内容，否则整个指令作为数据。 */
    private static String resolveContent(String text) {
        int contentStart = findContentStart(text);
        return contentStart >= 0 ? text.substring(contentStart) : text;
    }

    /** 提取修改指令中的新数据："为/改成/冒号"之后的内容（仅第一行）。 */
    private static String extractRowData(String text) {
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

    private static String resolveTitle(String text) {
        // 只取第一行做标题裁剪，避免多行指令把后续数据带进标题
        String normalized = firstLine(text)
            .replaceAll("生成|创建|制作|新建|做一个|覆盖|表格|Excel|excel|请|帮我", "")
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

    /** 未命中精确动作时，若文本包含"每行一条"形态的表格数据则当作生成。 */
    private static boolean containsTableData(String text) {
        long nonBlankLines = text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .count();
        return nonBlankLines >= 2;
    }

    private static ExcelService.QueryType queryType(String word) {
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
