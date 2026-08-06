package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;

import java.util.ArrayList;
import java.util.List;

import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_BUSINESS_RULE;
import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_FIELD_MAPPING;

/**
 * 添加知识操作：解析「触发词→内容」（字段映射）或「触发词=内容」（业务规则/操作示例/模板）
 * 后写入知识库；回复纯文字，不导出附件、不需要快照。
 */
public final class KnowledgeAddHandler implements ExcelOperationHandler {

    /** 触发词集合分隔符：逗号/顿号/分号（半角与全角）。 */
    private static final String KEYWORD_SEPARATORS = "，、,;";

    private final ExcelRagService excelRagService;

    public KnowledgeAddHandler(ExcelRagService excelRagService) {
        this.excelRagService = excelRagService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.KNOWLEDGE_ADD;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        if (excelRagService == null) {
            return OperationResult.failure("❌ 知识库未启用。");
        }
        String category = operation.param("category");
        String content = operation.param("content");
        int split = splitIndex(content);
        if (split < 0) {
            return OperationResult.failure(
                "❌ 无法解析知识内容「" + content + "」，需要用「→」或「=」分隔触发词与内容，"
                    + "示例：添加知识：字段映射 营收→营业收入");
        }
        List<String> keywords = splitKeywords(content.substring(0, split));
        String value = content.substring(split + 1).trim();
        if (keywords.isEmpty()) {
            return OperationResult.failure(
                "❌ 缺少触发词，示例：添加知识：字段映射 营收→营业收入");
        }
        if (value.isEmpty()) {
            return OperationResult.failure(
                "❌ 缺少知识内容，示例：添加知识：字段映射 营收→营业收入");
        }
        // 字段映射存标准列名；业务规则存规则文本；操作示例/模板存示例内容；同触发词重复添加时更新既有条目
        ExcelRagService.AddResult result;
        if (CATEGORY_FIELD_MAPPING.equals(category)) {
            result = excelRagService.upsert(category, keywords, value, null, null);
        } else if (CATEGORY_BUSINESS_RULE.equals(category)) {
            result = excelRagService.upsert(category, keywords, null, value, null);
        } else {
            result = excelRagService.upsert(category, keywords, null, null, value);
        }
        return OperationResult.success(
            (result.updated() ? "✅ 已更新知识：" : "✅ 已添加知识：")
                + ExcelRagKnowledge.labelOf(category)
                + " " + String.join("、", keywords) + "→" + value);
    }

    /** 分隔符定位：箭头或等号，取更靠左的一个（「营收→营业收入」取箭头，「毛利润=…」取等号）。 */
    private static int splitIndex(String content) {
        if (content == null) {
            return -1;
        }
        int arrow = content.indexOf("→");
        int equals = content.indexOf("=");
        if (arrow < 0) {
            return equals;
        }
        if (equals < 0) {
            return arrow;
        }
        return Math.min(arrow, equals);
    }

    /** 触发词集合切分：按逗号/顿号/分号拆分并去空。 */
    private static List<String> splitKeywords(String part) {
        List<String> keywords = new ArrayList<>();
        for (String keyword : part.split("[" + KEYWORD_SEPARATORS + "]")) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty()) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }
}
