package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;

import java.util.List;

/** 查看知识：回复知识库总条数与最近条目摘要（回复纯文字，不导出附件）。 */
public final class KnowledgeListHandler implements ExcelOperationHandler {

    /** 列表回复最多展示的条数。 */
    private static final int LIST_LIMIT = 10;

    private final ExcelRagService excelRagService;

    public KnowledgeListHandler(ExcelRagService excelRagService) {
        this.excelRagService = excelRagService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.KNOWLEDGE_LIST;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        if (excelRagService == null) {
            return OperationResult.failure("❌ 知识库未启用。");
        }
        List<ExcelRagKnowledge> recent = excelRagService.list(LIST_LIMIT);
        if (recent.isEmpty()) {
            return OperationResult.success(
                "📚 知识库还是空的，可以发送「添加知识：字段映射 营收→营业收入」来添加字段映射。");
        }
        StringBuilder reply = new StringBuilder(
            "📚 知识库共 " + excelRagService.count() + " 条（展示最近 " + recent.size() + " 条）：");
        for (ExcelRagKnowledge knowledge : recent) {
            reply.append("\n· [").append(ExcelRagKnowledge.labelOf(knowledge.getCategory()))
                .append("] ").append(String.join("、", knowledge.getKeywords()))
                .append(" → ").append(summary(knowledge));
        }
        return OperationResult.success(reply.toString());
    }

    /** 条目摘要：字段映射展示标准列名，其余展示规则/示例内容。 */
    private static String summary(ExcelRagKnowledge knowledge) {
        if (knowledge.getStandardField() != null && !knowledge.getStandardField().isBlank()) {
            return knowledge.getStandardField();
        }
        if (knowledge.getRule() != null && !knowledge.getRule().isBlank()) {
            return knowledge.getRule();
        }
        return knowledge.getExample() == null ? "" : knowledge.getExample();
    }
}
