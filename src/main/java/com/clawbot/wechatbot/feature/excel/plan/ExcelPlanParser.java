package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户文本解析成结构化 ExcelPlan。
 * 路由逻辑自重构前的 ExcelOperationSkill.dispatch 整体搬入，行为完全一致：
 * 回滚判定优先、查询/删除/修改/添加/生成/版本历史顺序、覆盖保护标记、内容提取规则。
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
    /** 版本历史指令：版本历史/查看版本/历史版本。 */
    private static final Pattern VERSION_HISTORY_CMD = Pattern.compile(
        "^(?:请|帮我)?\\s*(?:版本历史|查看版本|历史版本)(?:记录|列表)?\\s*$");

    /** 解析用户文本为 ExcelPlan；无法识别时返回 null（由调用方给出兜底提示）。 */
    public ExcelPlan parse(String userId, String text) {
        // 1. 回滚/撤销（放最前：避免「撤销删除第2行」这类说法被当成删除再次执行）
        if (isRollbackCommand(text)) {
            return plan(userId, op("1", ExcelOperationType.ROLLBACK, Map.of()));
        }

        // 2. 查询类（直接返回文字，不导出文件）
        ExcelOperation query = tryQuery(text);
        if (query != null) return plan(userId, query);

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
        String trimmed = text.trim().replaceFirst("^(?:请|帮我)\\s*", "");
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
        String normalized = text
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
