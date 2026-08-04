package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;

/** 删除知识：删除触发词命中该关键词的条目（回复纯文字，不导出附件）。 */
public final class KnowledgeDeleteHandler implements ExcelOperationHandler {

    private final ExcelRagService excelRagService;

    public KnowledgeDeleteHandler(ExcelRagService excelRagService) {
        this.excelRagService = excelRagService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.KNOWLEDGE_DELETE;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table) {
        if (excelRagService == null) {
            return OperationResult.failure("❌ 知识库未启用。");
        }
        String keyword = operation.param("keyword");
        if (excelRagService.deleteByKeyword(keyword)) {
            return OperationResult.success("✅ 已删除关键词「" + keyword + "」相关的知识。");
        }
        return OperationResult.failure("❌ 未找到关键词「" + keyword + "」相关的知识。");
    }
}
