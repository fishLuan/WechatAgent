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
        // 触发词未命中时回退到类别删除：用户可能按列表里显示的类别名（如「字段映射」）操作
        int removed = excelRagService.deleteByCategory(keyword);
        if (removed > 0) {
            return OperationResult.success(
                "✅ 已按类别「" + keyword + "」删除 " + removed + " 条知识。");
        }
        return OperationResult.failure(
            "❌ 未找到关键词「" + keyword + "」相关的知识。"
                + "可先「查看知识」确认，删除时输入触发词（如「销量」）或类别名（如「字段映射」）。");
    }
}
