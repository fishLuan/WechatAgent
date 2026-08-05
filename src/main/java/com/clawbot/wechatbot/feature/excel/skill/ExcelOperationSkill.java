package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.plan.AddRowHandler;
import com.clawbot.wechatbot.feature.excel.plan.ChartHandler;
import com.clawbot.wechatbot.feature.excel.plan.CreateTableHandler;
import com.clawbot.wechatbot.feature.excel.plan.DashboardHandler;
import com.clawbot.wechatbot.feature.excel.plan.DeduplicateHandler;
import com.clawbot.wechatbot.feature.excel.plan.DeleteRowHandler;
import com.clawbot.wechatbot.feature.excel.plan.ExcelOperationExecutor;
import com.clawbot.wechatbot.feature.excel.plan.ExcelOperationType;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlan;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanParser;
import com.clawbot.wechatbot.feature.excel.plan.ExcelPlanValidator;
import com.clawbot.wechatbot.feature.excel.plan.FillMissingHandler;
import com.clawbot.wechatbot.feature.excel.plan.FormatTableHandler;
import com.clawbot.wechatbot.feature.excel.plan.GroupSummaryHandler;
import com.clawbot.wechatbot.feature.excel.plan.KnowledgeAddHandler;
import com.clawbot.wechatbot.feature.excel.plan.KnowledgeAliasResolver;
import com.clawbot.wechatbot.feature.excel.plan.KnowledgeAliasResolver.ResolvedPlan;
import com.clawbot.wechatbot.feature.excel.plan.KnowledgeDeleteHandler;
import com.clawbot.wechatbot.feature.excel.plan.KnowledgeListHandler;
import com.clawbot.wechatbot.feature.excel.plan.OperationResult;
import com.clawbot.wechatbot.feature.excel.plan.QueryHandler;
import com.clawbot.wechatbot.feature.excel.plan.RollbackHandler;
import com.clawbot.wechatbot.feature.excel.plan.SortHandler;
import com.clawbot.wechatbot.feature.excel.plan.UpdateRowHandler;
import com.clawbot.wechatbot.feature.excel.plan.VersionHistoryHandler;
import com.clawbot.wechatbot.feature.excel.plan.WorkbookCopyHandler;
import com.clawbot.wechatbot.feature.excel.plan.WorkbookCreateHandler;
import com.clawbot.wechatbot.feature.excel.plan.WorkbookDeleteHandler;
import com.clawbot.wechatbot.feature.excel.plan.WorkbookListHandler;
import com.clawbot.wechatbot.feature.excel.plan.WorkbookRenameHandler;
import com.clawbot.wechatbot.feature.excel.plan.WorkbookSelectHandler;
import com.clawbot.wechatbot.feature.excel.plan.AuditListHandler;
import com.clawbot.wechatbot.feature.excel.plan.VersionDiffHandler;
import com.clawbot.wechatbot.feature.excel.service.ExcelAuditService;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Excel 表格操作技能：生成、增删改行、分析操作（排序/去重/分组汇总/缺失补全）、列聚合查询、表格式化/图表/汇总页、工作簿管理（多表）、知识管理（解析 → 别名解析 → 校验 → 执行四段式）。 */
@Component
public final class ExcelOperationSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "excel-operation";

    /** 无活动工作簿时的统一提示：其余操作（原 loadOrCreate 语义）必须先有当前表。 */
    private static final String NO_ACTIVE_WORKBOOK_HINT =
        "还没有表格，请先发送「新建表格 名字」创建，或上传 xlsx / 发带'表格'字样的截图导入。";
    /** 无法识别时的兜底文案：含全部工作簿管理指令。 */
    private static final String FALLBACK_MESSAGE =
        "无法识别 Excel 操作，支持的指令：生成表格（提供表头和数据）、"
            + "添加一行、修改第N行、删除第N行、按某列排序、按某列去重、"
            + "按某列汇总某列、补全某列空值、查询某列的最大/最小/合计/平均、"
            + "回滚到上一版本、查看版本历史、对比上一版、新建表格、查看表格列表、选择表格、"
            + "重命名表格、删除表格、复制表格、添加知识、查看知识、删除知识、查看操作日志、"
            + "美化表格（加标题/冻结首行/加筛选）、生成柱状图/折线图/饼图、生成汇总页。";
    /** 工作簿管理类操作（及操作日志）：不需要活动表，直接校验执行。 */
    private static final Set<ExcelOperationType> WORKBOOK_TYPES = Set.of(
        ExcelOperationType.WORKBOOK_CREATE,
        ExcelOperationType.WORKBOOK_LIST,
        ExcelOperationType.WORKBOOK_SELECT,
        ExcelOperationType.WORKBOOK_RENAME,
        ExcelOperationType.WORKBOOK_DELETE,
        ExcelOperationType.WORKBOOK_COPY,
        ExcelOperationType.AUDIT_LIST);

    private final ExcelService excelService;
    /** 知识库服务（可空：单参数构造器场景下为 null，别名解析与知识标注跳过，行为不变）。 */
    private final ExcelRagService excelRagService;
    /** 操作审计服务（可空：单/双参数构造器场景下为 null，审计记录跳过，行为不变）。 */
    private final ExcelAuditService excelAuditService;
    private final ExcelPlanParser planParser;
    private final ExcelPlanValidator planValidator;
    private final ExcelOperationExecutor executor;
    private final KnowledgeAliasResolver knowledgeAliasResolver;

    /** 测试/旧场景构造器：不注入知识库与审计（RAG/audit 为 null 时对应能力跳过）。 */
    public ExcelOperationSkill(ExcelService excelService) {
        this(excelService, null, null);
    }

    /** 测试/旧场景构造器：注入知识库但不注入审计（audit 为 null 时审计记录跳过）。 */
    public ExcelOperationSkill(ExcelService excelService, ExcelRagService excelRagService) {
        this(excelService, excelRagService, null);
    }

    /** Spring 装配：注入知识库与审计服务，计划执行前做列别名解析与知识标注，执行后写审计日志。 */
    @Autowired
    public ExcelOperationSkill(ExcelService excelService, ExcelRagService excelRagService,
                               ExcelAuditService excelAuditService) {
        this.excelService = excelService;
        this.excelRagService = excelRagService;
        this.excelAuditService = excelAuditService;
        this.planParser = new ExcelPlanParser();
        this.planValidator = new ExcelPlanValidator(excelService);
        this.knowledgeAliasResolver = new KnowledgeAliasResolver(excelRagService);
        this.executor = new ExcelOperationExecutor(List.of(
            new CreateTableHandler(excelService),
            new AddRowHandler(excelService),
            new UpdateRowHandler(excelService),
            new DeleteRowHandler(excelService),
            new QueryHandler(excelService),
            new SortHandler(excelService),
            new DeduplicateHandler(excelService),
            new GroupSummaryHandler(excelService),
            new FillMissingHandler(excelService),
            new FormatTableHandler(excelService),
            new ChartHandler(excelService),
            new DashboardHandler(excelService),
            new RollbackHandler(excelService),
            new VersionHistoryHandler(excelService),
            new WorkbookCreateHandler(excelService),
            new WorkbookListHandler(excelService),
            new WorkbookSelectHandler(excelService),
            new WorkbookRenameHandler(excelService),
            new WorkbookDeleteHandler(excelService),
            new WorkbookCopyHandler(excelService),
            new KnowledgeAddHandler(excelRagService),
            new KnowledgeListHandler(excelRagService),
            new KnowledgeDeleteHandler(excelRagService),
            new AuditListHandler(excelAuditService),
            new VersionDiffHandler(excelService)));
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

    /** 四段式：解析（文本 → 计划）→ 定位活动表 → 知识库别名解析 → 校验（计划 + 当前表格状态）→ 执行（按序，失败即停）。 */
    private SkillResult dispatch(String userId, String text) throws Exception {
        // 1. 解析：只产出结构化计划，不执行任何修改
        ExcelPlan plan = planParser.parse(userId, text);
        if (plan == null) {
            return SkillResult.failure(FALLBACK_MESSAGE);
        }
        // 2. 工作簿管理类操作（新建/列表/选择/重命名/删除/复制）不需要活动表，直接校验执行；
        //    生成/创建表格是唯一允许没有活动表的普通操作（原 loadOrCreate 语义：先建空表再填充）；
        //    其余操作作用于当前活动表，没有活动表时给出明确错误
        ExcelTable table = null;
        if (!isWorkbookManagementPlan(plan)) {
            table = excelService.getActiveWorkbook(userId);
            if (table == null) {
                if (isCreateTablePlan(plan)) {
                    String title = plan.operations().get(0).param("title");
                    table = excelService.createWorkbook(userId,
                        title == null || title.isBlank() ? "表格" : title);
                } else {
                    return SkillResult.failure(NO_ACTIVE_WORKBOOK_HINT);
                }
            }
        }
        // 3. 知识库别名解析：模糊匹配失败的列名按知识库字段映射替换，并记录映射说明（无 RAG 时原样返回）
        ResolvedPlan resolved = knowledgeAliasResolver.resolve(plan, table);
        // 4. 校验：按操作类型校验参数与当前表格状态，返回统一中文错误提示
        Optional<String> validationError = planValidator.validate(resolved.plan(), table);
        if (validationError.isPresent()) {
            return SkillResult.failure(validationError.get());
        }
        // 5. 执行：按计划顺序执行，遇到失败立即返回失败；异常（如公式错误取消导出）也写入审计
        OperationResult result;
        try {
            result = executor.execute(resolved.plan(), table);
        } catch (Exception error) {
            recordAudit(userId, resolved.plan(), table, OperationResult.failure(
                error.getMessage() == null ? error.toString() : error.getMessage()));
            throw error;
        }
        if (result.success()) {
            result = compositeSummary(resolved.plan(), result);
            result = annotateKnowledge(result, text, resolved.notes());
        }
        // 6. 审计：无论成败记录本次操作（operation 为各操作类型名拼接，detail 为结果文案）
        recordAudit(userId, resolved.plan(), table, result);
        return toSkillResult(result, table);
    }

    /** 审计记录：计划中各操作类型名用 + 拼接（如 SORT+GROUP_SUMMARY）；无审计服务时跳过。 */
    private void recordAudit(String userId, ExcelPlan plan, ExcelTable table,
                             OperationResult result) {
        if (excelAuditService == null) {
            return;
        }
        String operation = plan.operations().stream()
            .map(op -> op.type().name())
            .collect(java.util.stream.Collectors.joining("+"));
        excelAuditService.record(userId, table == null ? null : table.getId(),
            operation, result.success(), result.text());
    }

    /** 计划是否全部由工作簿管理类操作组成（工作簿管理指令只会产出单操作计划，此为统一判定）。 */
    private static boolean isWorkbookManagementPlan(ExcelPlan plan) {
        return plan.operations().stream().allMatch(op -> WORKBOOK_TYPES.contains(op.type()));
    }

    /** 计划是否为单个「生成/创建表格」操作（首次使用时允许没有活动表，先建空表再填充）。 */
    private static boolean isCreateTablePlan(ExcelPlan plan) {
        return plan.operations().size() == 1
            && plan.operations().get(0).type() == ExcelOperationType.CREATE_TABLE;
    }

    /** 成功回复前加注知识库标注：别名映射说明 + 命中的业务规则/操作示例（换行拼在操作文案前）。 */
    private OperationResult annotateKnowledge(OperationResult result, String text,
                                              List<String> notes) {
        List<String> annotations = new ArrayList<>(notes);
        if (excelRagService != null) {
            for (ExcelRagKnowledge knowledge : excelRagService.findRules(text)) {
                annotations.add(knowledgeNote(knowledge));
            }
        }
        if (annotations.isEmpty()) {
            return result;
        }
        return OperationResult.success(
            String.join("\n", annotations) + "\n" + result.text(), result.attachment());
    }

    /** 命中的业务规则/操作示例 → 一行标注文案。 */
    private static String knowledgeNote(ExcelRagKnowledge knowledge) {
        if (ExcelRagKnowledge.CATEGORY_BUSINESS_RULE.equals(knowledge.getCategory())) {
            return "📚 知识库规则：" + knowledge.getRule();
        }
        if (ExcelRagKnowledge.CATEGORY_TEMPLATE.equals(knowledge.getCategory())) {
            return "📚 知识库模板：" + knowledge.getExample();
        }
        return "📚 知识库示例：" + knowledge.getExample();
    }

    /** 复合任务（多步计划）成功时的汇总文案：✅ 已完成 N 步操作（最后一步：<最后一步文案>）。；单操作计划保持原样（回归）。 */
    private static OperationResult compositeSummary(ExcelPlan plan, OperationResult result) {
        if (!result.success() || plan.operations().size() <= 1) {
            return result;
        }
        String lastStep = result.text();
        if (lastStep.startsWith("✅ ")) {
            lastStep = lastStep.substring("✅ ".length());
        }
        return OperationResult.success(
            "✅ 已完成 " + plan.operations().size() + " 步操作（最后一步：" + lastStep + "）。",
            result.attachment());
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
