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
 * 另新增工作簿管理路由：新建/列表/选择/重命名/删除/复制表格（多工作簿管理，不需要活动表）。
 * 另新增操作日志（AUDIT_LIST）与版本对比（VERSION_DIFF）路由。
 * 解析器只产出计划，不执行任何修改。
 */
public final class ExcelPlanParser {

    private static final Pattern ROW_NUMBER = Pattern.compile(
        "第\\s*(\\d{1,3}|[一二两三四五六七八九十百]{1,4})\\s*行");
    private static final Pattern QUERY = Pattern.compile(
        "(?:查询|计算|看看|统计|找一下)?\\s*(?:表格里)?\\s*(.+?)\\s*的?\\s*"
            + "(最大值|最小(?:值)?|合计|总和|平均值|平均数|平均|行数|总数|总行数|多少行)");
    private static final Pattern SUM_PREFIX = Pattern.compile(
        "^(?:合计|统计)\\s*(.+?)(?:的)?(?:金额|总和|合计|数值|值)?$");
    /** 内容分隔标记：只在指令第一行内寻找，且取最靠右的一个，避免把数据行里的冒号/「为」误当分隔符。 */
    private static final Pattern CONTENT_MARKER = Pattern.compile(
        "为|改成|改为|数据(?:是|为)?|内容(?:是|为)?|[:：]");
    private static final Pattern ADD_PREFIX = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:添加|增加|加入|新增|加)\\s*"
            + "(?:一行|一条|1行|1条|行)?\\s*(?:数据)?\\s*[:：]?\\s*(.+)$");
    /** 规划层（LLM）改写指令时加的前缀：「在/向/给/对/把 X表格 中/里/内做Y」；
     *  表格上下文后必须是「中/里/内」或操作动词开头，避免误剥「把表格X改名为Y」这类合法指令。 */
    private static final Pattern PREFIX_TABLE_CONTEXT = Pattern.compile(
        "^(?:在|向|给|对|把|将)\\s*[“「\"']?[^“「\"',，。表格]*?[”」\"']?\\s*"
            + "(?:表格|工作簿|表)\\s*(?:(?:中|里|内)\\s*|(?=[按添加增修改更删除移除查询统计计算生成创建排序去重补汇总]))");
    /** 查询/统计类前缀里的表格上下文：「查询X表格中金额的最大值」→「查询金额的最大值」。 */
    private static final Pattern PREFIX_QUERY_TABLE = Pattern.compile(
        "^(?:查询|统计|计算)\\s*[“「\"']?[^“「\"',，。表格]*?[”」\"']?\\s*"
            + "(?:表格|工作簿|表)\\s*(?:中|里|内)?\\s*");
    /** 规划层改写指令时加的尾部括号说明：（首行为表头…）/（每行一条…）/（列顺序…）。 */
    private static final Pattern SUFFIX_PAREN_GUIDE = Pattern.compile(
        "[（(](?:首行为表头|每行一条|列顺序)[^）)]*[）)]\\s*$");
    /** 规划层改写指令时加的尾部说明：「，列顺序为产品、数量、金额」。 */
    private static final Pattern SUFFIX_COLUMN_ORDER = Pattern.compile(
        "[,，]?\\s*列顺序为[^，,。]*$");
    /** 规划层改写的表格格式：表头为：A,B；数据行：a,b；c,d（表头/数据行任意顺序，行间用分号或换行）。 */
    private static final Pattern PLANNER_TABLE_HEADERS = Pattern.compile(
        "表头为\\s*[:：]?\\s*([^；;\\n]+)");
    private static final Pattern PLANNER_TABLE_ROWS = Pattern.compile(
        "数据行[为是：:]*\\s*(.+?)(?:[,，]?\\s*表头为.*)?$", Pattern.DOTALL);
    /** 规划层把「覆盖」单独放第一行：覆盖\\n表头\\n行1\\n行2 → 生成覆盖表格：表头\\n行1\\n行2。 */
    private static final Pattern STANDALONE_COVER_LINE = Pattern.compile(
        "^(?:覆盖(?:表格|生成表格|创建表格)?)\\s*\\n");
    /** 版本历史指令：版本历史/查看版本/历史版本/查看版本历史，前缀「请/帮我」可任意组合与重复。 */
    private static final Pattern VERSION_HISTORY_CMD = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:版本历史|历史版本|查看版本(?:历史)?)(?:记录|列表)?\\s*$");
    /** 操作日志指令：查看操作日志/操作历史（按用户记录，不需要活动表）。 */
    private static final Pattern AUDIT_LIST_CMD = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:查看操作日志|操作历史)\\s*$");
    /** 版本对比指令：版本对比/对比上一版(本)（取最新版本快照与当前表对比）。 */
    private static final Pattern VERSION_DIFF_CMD = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:版本对比|对比上一版(?:本)?)\\s*$");
    /** 标题专用提取：创建名为「X」的表格（支持半角/弯引号与「」，X 到「的」为止），无「名为」时走关键词裁剪。 */
    private static final Pattern NAMED_TITLE = Pattern.compile(
        "名为\\s*(?:\"([^\"”」]+)\"|“([^”」]+)”|「([^」]+)」|([^\"”」]+?))\\s*的");
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
    /** 工作簿管理指令：新建表格/新建工作簿 X（名称像表格数据时让位给生成表格）。 */
    private static final Pattern WORKBOOK_CREATE_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*新建(?:表格|工作簿)(?:名字)?\\s*[:：]?\\s*(.+)$");
    /** 工作簿列表指令：我的表格/表格列表/查看表格列表/有哪些表格/查看所有工作簿/所有工作簿/我的工作簿。 */
    private static final Pattern WORKBOOK_LIST_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*(?:我的表格|表格列表|查看表格列表|有哪些表格|"
            + "查看所有工作簿|所有工作簿|我的工作簿)\\s*$");
    /** 选择表格指令：选择/切换(到)/打开 表格或工作簿 X（支持冒号，如「切换工作簿：季度销售」）。 */
    private static final Pattern WORKBOOK_SELECT_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*(?:选择|切换(?:到)?|打开)(?:表格|工作簿)\\s*[:：]?\\s*(.+?)\\s*$");
    /** 重命名表格指令：重命名表格 X为Y / 把表格X改名为Y。 */
    private static final Pattern WORKBOOK_RENAME_VERB = Pattern.compile(
        "^(?:请|帮我\\s*)*重命名(?:表格|工作簿)?\\s*(.+?)为(.+?)\\s*$");
    private static final Pattern WORKBOOK_RENAME_PREFIX = Pattern.compile(
        "^(?:请|帮我\\s*)*把表格\\s*(.+?)改名为(.+?)\\s*$");
    /** 重命名表格指令（通用说法）：X[名字/名称][改为/改成/改名为/更名为/重命名为]Y，可带「把」前缀。 */
    private static final Pattern RENAME_CMD = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:把\\s*)?[“«\"']?([^”»\"']+?)[”»\"']?\\s*"
            + "(?:名字|名称)?\\s*(?:改为|改成|改名为|更名为|重命名为)\\s*"
            + "[“«\"']?([^”»\"']+?)[”»\"']?\\s*$");
    /** 通用重命名防误判：以其他操作动词开头的指令不按重命名处理（如「添加行：把金额改为100」）。 */
    private static final Pattern RENAME_FORBIDDEN_PREFIX = Pattern.compile(
        "^(?:添加|增加|加入|新增|加|生成|创建|制作|新建|修改|更新|删除|移除|导出|查询|统计|排序|去重|补全|撤销|回滚|恢复)");
    /** 删除表格指令：删除表格/工作簿 X。 */
    private static final Pattern WORKBOOK_DELETE_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*删除(?:表格|工作簿)\\s*(.+?)\\s*$");
    /** 复制表格指令：复制表格/工作簿 X。 */
    private static final Pattern WORKBOOK_COPY_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*复制(?:表格|工作簿)?\\s*(.+?)\\s*$");
    /** 表格式化指令：加标题/标题为/设置标题 X（X 到分隔符为止）。 */
    private static final Pattern FORMAT_TITLE = Pattern.compile(
        "(?:加标题|标题为|设置标题)\\s*[:：]?\\s*(.+?)\\s*(?:[，,、；;。]|$)");
    /** 表格式化指令：冻结首行/冻结表头 → freezeHeader。 */
    private static final Pattern FORMAT_FREEZE = Pattern.compile("冻结(?:首行|表头)");
    /** 表格式化指令：加筛选/自动筛选 → autoFilter。 */
    private static final Pattern FORMAT_FILTER = Pattern.compile("(?:加筛选|自动筛选)");
    /** 表格式化指令：美化表格/格式化 → 全部默认（无标题，冻结 + 筛选开）。 */
    private static final Pattern FORMAT_BEAUTIFY = Pattern.compile("(?:美化表格|格式化)");
    /** 图表指令：生成X图：分类,数值 / 折线图 分类,数值（冒号或空格分隔）。 */
    private static final Pattern CHART_PLAIN = Pattern.compile(
        "^(?:请|帮我\\s*)*(?:生成|做一个)?\\s*(柱状图|柱形图|折线图|饼图)\\s*[:：]?\\s*(.*)$");
    /** 图表指令：按X生成Y图（V）或 按X生成V图（数值列带括号；图型词可直接跟在「生成」后）。 */
    private static final Pattern CHART_BY_PAREN = Pattern.compile(
        "^(?:请|帮我\\s*)*按\\s*(.+?)\\s*生成\\s*(.*?)\\s*(柱状图|柱形图|折线图|饼图)"
            + "\\s*[（(]\\s*(.+?)\\s*[）)]\\s*$");
    /** 图表指令：按X生成V图（数值列紧跟图型词）。 */
    private static final Pattern CHART_BY_JOINED = Pattern.compile(
        "^(?:请|帮我\\s*)*按\\s*(.+?)\\s*生成\\s*(.*?)\\s*(柱状图|柱形图|折线图|饼图)\\s*$");
    /** 汇总页指令：生成汇总页/汇总页/dashboard（大小写不敏感）。 */
    private static final Pattern DASHBOARD_CMD = Pattern.compile(
        "^(?:请|帮我\\s*)*(?:生成汇总页|汇总页|dashboard)\\s*$", Pattern.CASE_INSENSITIVE);
    /** 导出表格指令：导出/下载表格（可带表名）、把X表格发给我、给我表格文件、发我表格（发当前活动表完整版，不修改数据）。 */
    private static final Pattern EXPORT_CMD = Pattern.compile(
        "^(?:(?:请|帮我)\\s*)*(?:导出|下载)\\s*[“「]?[^”」]*?[”」]?\\s*(?:表格|工作簿)?\\s*$"
            + "|^(?:(?:请|帮我)\\s*)*(?:把|将)\\s*[“「]?[^”」]*?[”」]?\\s*表格\\s*(?:发|发送|发给)(?:给)?\\s*我?\\s*$"
            + "|^(?:(?:请|帮我)\\s*)*(?:发|发送)\\s*(?:给)?\\s*我?\\s*[“「]?[^”」]*?[”」]?\\s*表格\\s*$"
            + "|^(?:(?:请|帮我)\\s*)*给我\\s*(?:发)?\\s*(?:表格|Excel|excel|xlsx)?\\s*(?:文件)?\\s*$");
    /** 片段尾部残留分隔符（切分点前的标点/连接词），清理时按长度从长到短尝试。 */
    private static final List<String> SEPARATOR_TAIL_WORDS = List.of(
        "然后", "接着", "同时", "并且", "并", "再", "，", "、", "；", ",", ";");

    /** 解析用户文本为 ExcelPlan；无法识别时返回 null（由调用方给出兜底提示）。 */
    public ExcelPlan parse(String userId, String text) {
        // 重命名指令先于归一化判定：避免「把报表改名为X」被表格上下文前缀剥离破坏
        ExcelOperation rename = tryRename(text);
        if (rename != null) {
            return plan(userId, rename);
        }
        text = normalizeInstruction(text);
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

        // 工作簿管理指令（新建/列表/选择/重命名/删除/复制）：放在知识管理之前、
        // 「生成表格」之前判定（「新建表格 X」不能被当成生成表格）
        ExcelPlan workbook = tryWorkbook(userId, text);
        if (workbook != null) return workbook;

        // 知识管理指令（必须在「添加行」之前判定：「添加知识：…」会命中添加行前缀正则，避免被当成加行）
        ExcelPlan knowledge = tryKnowledge(userId, text);
        if (knowledge != null) return knowledge;

        // 表格式化 / 图表 / 汇总页：分析类与知识管理之后、删除行之前判定。
        // 必须在「添加行」（「加标题」「加筛选」会被「加」字宽匹配吞掉）与「生成表格」
        // （「生成柱状图」「生成汇总页」含「生成」）分支之前
        ExcelOperation format = tryFormat(text);
        if (format != null) return plan(userId, format);
        ExcelOperation chart = tryChart(text);
        if (chart != null) return plan(userId, chart);
        ExcelOperation dashboard = tryDashboard(text);
        if (dashboard != null) return plan(userId, dashboard);

        // 导出表格（方案一：内容操作只回文字，需要当前完整文件时发送「导出表格」）
        if (EXPORT_CMD.matcher(text).matches()) {
            return plan(userId, op("1", ExcelOperationType.EXPORT, Map.of()));
        }

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

        // 8. 操作日志 / 版本对比（纯查询类，放在最后避免与既有指令混淆）
        if (AUDIT_LIST_CMD.matcher(text).matches()) {
            return plan(userId, op("1", ExcelOperationType.AUDIT_LIST, Map.of()));
        }
        if (VERSION_DIFF_CMD.matcher(text).matches()) {
            return plan(userId, op("1", ExcelOperationType.VERSION_DIFF, Map.of()));
        }

        return null;
    }

    /** 重命名路由：重命名X为Y / 把表格X改名为Y / X[名字/名称][改为/改成/改名为/更名为/重命名为]Y。 */
    private static ExcelOperation tryRename(String text) {
        if (text == null) {
            return null;
        }
        Matcher verb = WORKBOOK_RENAME_VERB.matcher(text);
        if (verb.matches()) {
            return renameOperation(verb.group(1), verb.group(2));
        }
        Matcher prefix = WORKBOOK_RENAME_PREFIX.matcher(text);
        if (prefix.matches()) {
            return renameOperation(prefix.group(1), prefix.group(2));
        }
        if (RENAME_FORBIDDEN_PREFIX.matcher(text).find()) {
            return null; // 以其他操作动词开头，不按重命名处理
        }
        Matcher generic = RENAME_CMD.matcher(text);
        if (generic.matches()) {
            return renameOperation(generic.group(1), generic.group(2));
        }
        return null;
    }

    private static ExcelOperation renameOperation(String name, String newTitle) {
        return op("1", ExcelOperationType.WORKBOOK_RENAME,
            Map.of("name", name.trim(), "newTitle", newTitle.trim()));
    }

    /**
     * 指令归一化：剥掉规划层（LLM）改写指令时加的前缀与尾部说明，
     * 避免「在X表格中…」「（首行为表头，每行一条数据）」「，列顺序为…」污染路由与数据提取。
     * 只剥离特征明显的规划层包装，不改变用户原始数据文本。
     */
    private static String normalizeInstruction(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        // 规划层把「覆盖」作为独立首行时，合并进生成指令前缀
        normalized = STANDALONE_COVER_LINE.matcher(normalized).replaceFirst("生成覆盖表格：");
        normalized = SUFFIX_PAREN_GUIDE.matcher(normalized).replaceAll("");
        normalized = SUFFIX_COLUMN_ORDER.matcher(normalized).replaceAll("");
        normalized = PREFIX_TABLE_CONTEXT.matcher(normalized).replaceFirst("");
        normalized = PREFIX_QUERY_TABLE.matcher(normalized).replaceFirst("");
        normalized = normalizePlannerTableFormat(normalized);
        return normalized;
    }

    /**
     * 规划层（LLM）改写后的表格格式还原：把「表头为：A,B；数据行：a,b；c,d」还原成
     * 「生成[覆盖]表格：A,B\n a,b\nc,d」，让既有表头/数据提取逻辑直接生效；
     * 原文含「覆盖」确认词时保留在第一行，避免覆盖保护误拦截。
     */
    private static String normalizePlannerTableFormat(String text) {
        Matcher headers = PLANNER_TABLE_HEADERS.matcher(text);
        Matcher rows = PLANNER_TABLE_ROWS.matcher(text);
        if (!headers.find() || !rows.find()) {
            return text;
        }
        String headerLine = headers.group(1).trim();
        if (headerLine.isBlank()) {
            return text;
        }
        // 行分隔只认中文/半角分号与换行，不折叠单元格内部空格
        String rowsJoined = rows.group(1).trim()
            .replaceAll("[；;]", "\n")
            .replaceAll("\\n\\s*", "\n")
            .trim();
        String prefix = text.contains("覆盖") ? "生成覆盖表格：" : "生成表格：";
        return prefix + headerLine + "\n" + rowsJoined;
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

    /**
     * 工作簿管理路由：新建 → 列表 → 选择 → 重命名 → 删除 → 复制（关键词互不重叠，顺序固定）。
     * 任一指令缺参数（如「选择表格」无名称）时返回 null 交给兜底提示。
     */
    private ExcelPlan tryWorkbook(String userId, String text) {
        Matcher create = WORKBOOK_CREATE_CMD.matcher(text);
        if (create.matches()) {
            String title = create.group(1).trim();
            // 名称像表格数据（换行或分隔符）时让位给生成表格（「新建表格：姓名,城市\n张三,北京」仍是生成）
            if (!title.isBlank() && !looksLikeTableData(title)) {
                return plan(userId, op("1", ExcelOperationType.WORKBOOK_CREATE,
                    Map.of("title", title)));
            }
            return null;
        }
        if (WORKBOOK_LIST_CMD.matcher(text).matches()) {
            return plan(userId, op("1", ExcelOperationType.WORKBOOK_LIST, Map.of()));
        }
        Matcher select = WORKBOOK_SELECT_CMD.matcher(text);
        if (select.matches()) {
            String name = select.group(1).trim();
            if (!name.isBlank()) {
                return plan(userId, op("1", ExcelOperationType.WORKBOOK_SELECT,
                    Map.of("name", name)));
            }
        }
        Matcher rename = WORKBOOK_RENAME_VERB.matcher(text);
        if (rename.matches()) {
            return renamePlan(userId, rename.group(1).trim(), rename.group(2).trim());
        }
        rename = WORKBOOK_RENAME_PREFIX.matcher(text);
        if (rename.matches()) {
            return renamePlan(userId, rename.group(1).trim(), rename.group(2).trim());
        }
        Matcher delete = WORKBOOK_DELETE_CMD.matcher(text);
        if (delete.matches()) {
            String name = delete.group(1).trim();
            if (!name.isBlank()) {
                return plan(userId, op("1", ExcelOperationType.WORKBOOK_DELETE,
                    Map.of("name", name)));
            }
        }
        Matcher copy = WORKBOOK_COPY_CMD.matcher(text);
        if (copy.matches()) {
            String name = copy.group(1).trim();
            if (!name.isBlank()) {
                return plan(userId, op("1", ExcelOperationType.WORKBOOK_COPY,
                    Map.of("name", name)));
            }
        }
        return null;
    }

    /**
     * 表格式化路由：整句关键词识别，可组合（如「加标题 销售报表，冻结首行，加筛选」产出单个 FORMAT_TABLE）。
     * 标题取「加标题/标题为/设置标题」后到分隔符为止；冻结/筛选/美化按关键词置位；
     * 「美化表格/格式化」补齐全部默认项（无标题，冻结 + 筛选开）。
     */
    private ExcelOperation tryFormat(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher title = FORMAT_TITLE.matcher(text);
        if (title.find()) {
            String value = title.group(1).trim();
            if (!value.isBlank()) {
                params.put("title", value);
            }
        }
        if (FORMAT_FREEZE.matcher(text).find()) {
            params.put("freezeHeader", "true");
        }
        if (FORMAT_FILTER.matcher(text).find()) {
            params.put("autoFilter", "true");
        }
        if (FORMAT_BEAUTIFY.matcher(text).find()) {
            params.putIfAbsent("freezeHeader", "true");
            params.putIfAbsent("autoFilter", "true");
        }
        return params.isEmpty() ? null : op("1", ExcelOperationType.FORMAT_TABLE, params);
    }

    /**
     * 图表路由：图型词 → BAR/LINE/PIE，分类列/数值列取「图型词 分类,数值」、
     * 「按X生成Y图（V）」或「按X生成V图」三种形态；数值列缺失时不产出该参数交给校验器提示。
     */
    private ExcelOperation tryChart(String text) {
        // 多图表：一句话多张图（如「生成折线图：A,B、生成饼图：C,D」）→ 一张工作簿多张图
        List<ChartFragment> fragments = splitChartFragments(text);
        if (fragments.size() >= 2) {
            return multiChartOperation(fragments);
        }
        Matcher plain = CHART_PLAIN.matcher(text);
        if (plain.matches()) {
            List<String> columns = splitChartColumns(plain.group(2));
            Map<String, String> params = new LinkedHashMap<>();
            params.put("chartType", chartType(plain.group(1)));
            if (!columns.isEmpty()) {
                params.put("categoryColumn", columns.get(0));
            }
            if (columns.size() > 1) {
                params.put("valueColumn", columns.get(1));
            }
            return op("1", ExcelOperationType.CHART, params);
        }
        Matcher paren = CHART_BY_PAREN.matcher(text);
        if (paren.matches()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("chartType", chartType(paren.group(3)));
            params.put("categoryColumn", paren.group(1).trim());
            String value = paren.group(4).trim();
            if (!value.isBlank()) {
                params.put("valueColumn", value);
            }
            return op("1", ExcelOperationType.CHART, params);
        }
        Matcher joined = CHART_BY_JOINED.matcher(text);
        if (joined.matches()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("chartType", chartType(joined.group(3)));
            params.put("categoryColumn", joined.group(1).trim());
            String value = joined.group(2).trim();
            if (!value.isBlank()) {
                params.put("valueColumn", value);
            }
            return op("1", ExcelOperationType.CHART, params);
        }
        return null;
    }

    /** 多图表片段：图表类型 + 分类列 + 数值列。 */
    private record ChartFragment(String chartType, String categoryColumn,
                                 String valueColumn) {}

    /** 按「、；;」切分多图表指令，仅保留自包含的图表片段（列数 ≥ 2）。 */
    private static List<ChartFragment> splitChartFragments(String text) {
        List<ChartFragment> fragments = new ArrayList<>();
        for (String part : text.split("[、；;]")) {
            String fragment = part.trim();
            if (fragment.isBlank()) continue;
            Matcher plain = CHART_PLAIN.matcher(fragment);
            if (plain.matches()) {
                List<String> columns = splitChartColumns(plain.group(2));
                if (columns.size() >= 2) {
                    fragments.add(new ChartFragment(
                        chartType(plain.group(1)), columns.get(0), columns.get(1)));
                }
            }
        }
        return fragments;
    }

    /** 多图表操作：首图参数 + extraCharts（"类型|分类|数值" 用 | 连接）。 */
    private static ExcelOperation multiChartOperation(List<ChartFragment> fragments) {
        Map<String, String> params = new LinkedHashMap<>();
        ChartFragment first = fragments.get(0);
        params.put("chartType", first.chartType());
        params.put("categoryColumn", first.categoryColumn());
        params.put("valueColumn", first.valueColumn());
        StringBuilder extra = new StringBuilder();
        for (int i = 1; i < fragments.size(); i++) {
            ChartFragment f = fragments.get(i);
            if (extra.length() > 0) extra.append('|');
            extra.append(f.chartType()).append('|')
                .append(f.categoryColumn()).append('|')
                .append(f.valueColumn());
        }
        params.put("extraCharts", extra.toString());
        return op("1", ExcelOperationType.CHART, params);
    }

    /** 汇总页路由：生成汇总页/汇总页/dashboard（大小写不敏感）。 */
    private ExcelOperation tryDashboard(String text) {
        if (DASHBOARD_CMD.matcher(text).matches()) {
            return op("1", ExcelOperationType.DASHBOARD, Map.of());
        }
        return null;
    }

    /** 图型词 → 图表类型枚举名：柱状图/柱形图 → BAR，折线图 → LINE，饼图 → PIE。 */
    private static String chartType(String word) {
        return switch (word) {
            case "柱状图", "柱形图" -> "BAR";
            case "折线图" -> "LINE";
            default -> "PIE";
        };
    }

    /** 图表「分类,数值」列拆分：按中英文逗号/顿号/分号切分，去空白后保留非空段。 */
    private static List<String> splitChartColumns(String body) {
        List<String> columns = new ArrayList<>();
        for (String part : body.split("[，,、；;]")) {
            String column = part.trim();
            if (!column.isBlank()) {
                columns.add(column);
            }
        }
        return columns;
    }

    /** 重命名计划：名称与新标题均非空才产出（缺失时让位，由兜底文案提示）。 */
    private static ExcelPlan renamePlan(String userId, String name, String newTitle) {
        if (name.isBlank() || newTitle.isBlank()) {
            return null;
        }
        return plan(userId, op("1", ExcelOperationType.WORKBOOK_RENAME,
            Map.of("name", name, "newTitle", newTitle)));
    }

    /** 名称是否像表格数据：含换行或表格分隔符（此时「新建表格 X」按生成表格处理）。 */
    private static boolean looksLikeTableData(String name) {
        return name.contains("\n") || name.matches(".*[\\t,，;；|].*");
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
        String head = firstLine(text);
        Matcher named = NAMED_TITLE.matcher(head);
        if (named.find()) {
            String title = named.group(1) != null ? named.group(1)
                : (named.group(2) != null ? named.group(2)
                    : (named.group(3) != null ? named.group(3) : named.group(4)));
            title = title.trim();
            if (!title.isBlank()) {
                return title.substring(0, Math.min(30, title.length()));
            }
        }
        String normalized = head
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
