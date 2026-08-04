package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.plan.AddRowHandler;
import com.clawbot.wechatbot.feature.excel.plan.CreateTableHandler;
import com.clawbot.wechatbot.feature.excel.plan.DeleteRowHandler;
import com.clawbot.wechatbot.feature.excel.plan.ExcelOperationExecutor;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlan;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanParser;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanValidator;
import com.clawbot.wechatbot.feature.excel.plan.OperationResult;
import com.clawbot.wechatbot.feature.excel.plan.QueryHandler;
import com.clawbot.wechatbot.feature.excel.plan.RollbackHandler;
import com.clawbot.wechatbot.feature.excel.plan.UpdateRowHandler;
import com.clawbot.wechatbot.feature.excel.plan.VersionHistoryHandler;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Excel 表格操作技能：生成、增删改行、列聚合查询（解析 → 校验 → 执行三段式）。 */
@Component
public final class ExcelOperationSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "excel-operation";

    private final ExcelService excelService;
    private final ExcelPlanParser planParser;
    private final ExcelPlanValidator planValidator;
    private final ExcelOperationExecutor executor;

    public ExcelOperationSkill(ExcelService excelService) {
        this.excelService = excelService;
        this.planParser = new ExcelPlanParser();
        this.planValidator = new ExcelPlanValidator(excelService);
        this.executor = new ExcelOperationExecutor(List.of(
            new CreateTableHandler(excelService),
            new AddRowHandler(excelService),
            new UpdateRowHandler(excelService),
            new DeleteRowHandler(excelService),
            new QueryHandler(excelService),
            new RollbackHandler(excelService),
            new VersionHistoryHandler(excelService)));
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

    /** 三段式：解析（文本 → 计划）→ 校验（计划 + 当前表格状态）→ 执行（按序，失败即停）。 */
    private SkillResult dispatch(String userId, String text) throws Exception {
        // 1. 解析：只产出结构化计划，不执行任何修改
        ExcelPlan plan = planParser.parse(userId, text);
        if (plan == null) {
            return SkillResult.failure(
                "无法识别 Excel 操作，支持的指令：生成表格（提供表头和数据）、"
                    + "添加一行、修改第N行、删除第N行、查询某列的最大/最小/合计/平均、"
                    + "回滚到上一版本、查看版本历史。");
        }
        ExcelTable table = excelService.loadOrCreate(userId, "表格");
        // 2. 校验：按操作类型校验参数与当前表格状态，返回统一中文错误提示
        Optional<String> validationError = planValidator.validate(plan, table);
        if (validationError.isPresent()) {
            return SkillResult.failure(validationError.get());
        }
        // 3. 执行：按计划顺序执行，遇到失败立即返回失败
        OperationResult result = executor.execute(plan, table);
        return toSkillResult(result, table);
    }

    /** 把操作结果转成 SkillResult：失败直接返回；成功且带附件时导出 xlsx 附件。 */
    private SkillResult toSkillResult(OperationResult result, ExcelTable table) {
        if (!result.success()) {
            return SkillResult.failure(result.text());
        }
        if (result.attachment() == null) {
            return SkillResult.success(result.text());
        }
        AgentAttachment attachment = new AgentAttachment(
            AgentAttachment.AttachmentType.FILE,
            result.attachment(),
            "excel-" + System.currentTimeMillis() + ".xlsx",
            "Excel 表格（" + table.getHeaders().size() + "列×"
                + table.getRows().size() + "行）");
        return SkillResult.success(result.text(), List.of(attachment));
    }
}
