package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 解析路由测试：与重构前 dispatch 的路由行为保持一致（回滚优先、覆盖标记、内容提取）。 */
class ExcelPlanParserTests {

    private final ExcelPlanParser parser = new ExcelPlanParser();

    private ExcelOperation parseSingle(String text) {
        ExcelPlan plan = parser.parse("user-1", text);
        assertNotNull(plan);
        assertEquals(1, plan.operations().size());
        return plan.operations().get(0);
    }

    // ============================
    // 回滚判定优先
    // ============================
    @Test
    void undoPhraseWithDeleteDescriptionRoutesToRollback() {
        ExcelOperation op = parseSingle("撤销删除第2行");
        assertEquals(ExcelOperationType.ROLLBACK, op.type());
    }

    @Test
    void rollbackAliasesAndHelpPrefixRouteToRollback() {
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("回滚").type());
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("撤销").type());
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("恢复").type());
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("请撤销删除第2行").type());
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("帮我回滚").type());
    }

    // ============================
    // 查询路由
    // ============================
    @Test
    void queryRoutesToQueryWithColumnAndType() {
        ExcelOperation op = parseSingle("查询金额的最大值");
        assertEquals(ExcelOperationType.QUERY, op.type());
        assertEquals("金额", op.param("column"));
        assertEquals("MAX", op.param("queryType"));
    }

    @Test
    void averageAndCountWordsMapToQueryType() {
        assertEquals("AVERAGE", parseSingle("查询成绩的平均值").param("queryType"));
        assertEquals("COUNT", parseSingle("统计人数的多少行").param("queryType"));
    }

    @Test
    void sumPrefixRoutesToSumQuery() {
        ExcelOperation op = parseSingle("合计金额");
        assertEquals(ExcelOperationType.QUERY, op.type());
        assertEquals("金额", op.param("column"));
        assertEquals("SUM", op.param("queryType"));
    }

    // ============================
    // 删除/修改/添加行
    // ============================
    @Test
    void deleteRowRoutesToDeleteWithRowNumber() {
        ExcelOperation op = parseSingle("删除第2行");
        assertEquals(ExcelOperationType.DELETE_ROW, op.type());
        assertEquals("2", op.param("rowNumber"));
    }

    @Test
    void deleteRowSupportsChineseRowNumber() {
        ExcelOperation op = parseSingle("删除第三行");
        assertEquals(ExcelOperationType.DELETE_ROW, op.type());
        assertEquals("3", op.param("rowNumber"));
    }

    @Test
    void updateRowExtractsRowNumberAndCells() {
        ExcelOperation op = parseSingle("修改第2行为 张三,25,北京");
        assertEquals(ExcelOperationType.UPDATE_ROW, op.type());
        assertEquals("2", op.param("rowNumber"));
        assertEquals("张三,25,北京", op.param("cells"));
    }

    @Test
    void updateRowWithoutDataHasBlankCells() {
        ExcelOperation op = parseSingle("修改第2行");
        assertEquals(ExcelOperationType.UPDATE_ROW, op.type());
        assertEquals("2", op.param("rowNumber"));
        assertEquals("", op.param("cells"));
    }

    @Test
    void addRowRoutesToAddWithCells() {
        ExcelOperation op = parseSingle("添加一行：张三,25,北京");
        assertEquals(ExcelOperationType.ADD_ROW, op.type());
        assertEquals("张三,25,北京", op.param("cells"));
    }

    // ============================
    // 生成表格：覆盖标记与内容提取
    // ============================
    @Test
    void createTableKeepsAllRowsAfterColon() {
        ExcelOperation op = parseSingle("生成覆盖表格：姓名,城市\n张三,北京\n李四,上海");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("姓名,城市", op.param("headers"));
        assertEquals("张三,北京\n李四,上海", op.param("rows"));
        assertEquals("true", op.param("overwrite"));
        // 重建内容后解析应得到完整数据（冒号后多行不丢失）
        ExcelService.ParsedTable parsed = ExcelService.parseTableText(
            OperationChecks.rebuildContent(op));
        assertEquals(2, parsed.rows().size());
    }

    @Test
    void createTableWithoutCoverMarksOverwriteFalse() {
        ExcelOperation op = parseSingle("生成表格：姓名,城市");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("false", op.param("overwrite"));
    }

    /** 覆盖保护只认指令第一行：数据行里的「覆盖」不能解锁覆盖。 */
    @Test
    void coverKeywordInsideDataDoesNotUnlockOverwrite() {
        ExcelOperation op = parseSingle("生成表格：姓名,城市\n张三,覆盖区域");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("false", op.param("overwrite"));
    }

    /** 纯粘贴数据时，数据行里的冒号不能被当作内容分隔标记截断。 */
    @Test
    void plainPasteWithColonInDataIsNotChopped() {
        ExcelOperation op = parseSingle("姓名,城市,备注\n张三,北京,时间：9点");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("姓名,城市,备注", op.param("headers"));
        assertEquals("张三,北京,时间：9点", op.param("rows"));
    }

    @Test
    void createTableResolvesTitleFromInstruction() {
        ExcelOperation op = parseSingle("生成覆盖表格：姓名,城市");
        assertEquals("我的表格", op.param("title"));
    }

    /** 「创建名为X的表格，表头为…」：标题取 X，表头/数据/覆盖标记正确提取。 */
    @Test
    void namedPhraseExtractsTitleAndHeaders() {
        ExcelOperation op = parseSingle("创建名为“季度销售”的Excel表格，表头为：产品,数量,金额");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("季度销售", op.param("title"));
        assertEquals("产品,数量,金额", op.param("headers"));
        assertEquals("", op.param("rows"));
        assertEquals("false", op.param("overwrite"));
    }

    /** 无引号/弯引号混合也能提取：名为X的表格（X 内不含「的」时取完整）。 */
    @Test
    void namedPhraseWithoutQuotesExtractsTitle() {
        ExcelOperation op = parseSingle("创建名为季度销售的表");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("季度销售", op.param("title"));
    }

    /** 规划层改写：创建指令尾部带「（首行为表头，每行一条数据）」说明时，表头/标题仍正确提取。 */
    @Test
    void createTableWithTrailingParentheticalGuide() {
        ExcelOperation op = parseSingle(
            "创建名为“季度销售”的Excel表格，表头为：产品,数量,金额（首行为表头，每行一条数据）");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("季度销售", op.param("title"));
        assertEquals("产品,数量,金额", op.param("headers"));
        assertEquals("", op.param("rows"));
        assertEquals("false", op.param("overwrite"));
    }

    /** 规划层改写：添加行变成「在X表格中添加一行数据：Y，列顺序为…」时仍能提取出干净数据。 */
    @Test
    void addRowInTablePhraseAfterPlannerExpansion() {
        ExcelOperation op = parseSingle(
            "在“季度销售”Excel表格中添加一行数据：产品A,100,25，列顺序为产品、数量、金额");
        assertEquals(ExcelOperationType.ADD_ROW, op.type());
        assertEquals("产品A,100,25", op.param("cells"));
    }

    /** 用户短句「添加行：X」（省掉「一」）也能正确识别，不再把「行」带进数据。 */
    @Test
    void terseAddRowWithoutOneWord() {
        ExcelOperation op = parseSingle("添加行：产品A,100,25");
        assertEquals(ExcelOperationType.ADD_ROW, op.type());
        assertEquals("产品A,100,25", op.param("cells"));
    }

    /** 规划层改写：排序变成「对X表格按Y从大到小排序」时仍正确路由。 */
    @Test
    void sortInTablePhraseAfterPlannerExpansion() {
        ExcelOperation op = parseSingle("对“季度销售”表格按数量从大到小排序");
        assertEquals(ExcelOperationType.SORT, op.type());
        assertEquals("数量", op.param("column"));
        assertEquals("DESC", op.param("direction"));
    }

    /** 规划层改写：查询变成「查询X表格中Y的最大值」时列名不带表格上下文。 */
    @Test
    void queryInTablePhraseAfterPlannerExpansion() {
        ExcelOperation op = parseSingle("查询“季度销售”表格中金额的最大值");
        assertEquals(ExcelOperationType.QUERY, op.type());
        assertEquals("金额", op.param("column"));
        assertEquals("MAX", op.param("queryType"));
    }

    // ============================
    // 排序路由
    // ============================
    @Test
    void sortRoutesToSortWithAscDefault() {
        ExcelOperation op = parseSingle("按销售额排序");
        assertEquals(ExcelOperationType.SORT, op.type());
        assertEquals("销售额", op.param("column"));
        assertEquals("ASC", op.param("direction"));
    }

    @Test
    void sortDirectionWordsMapToAscAndDesc() {
        assertEquals("DESC", parseSingle("按销售额倒序").param("direction"));
        assertEquals("DESC", parseSingle("按销售额降序").param("direction"));
        assertEquals("DESC", parseSingle("按销售额从大到小").param("direction"));
        assertEquals("ASC", parseSingle("按销售额升序").param("direction"));
        assertEquals("ASC", parseSingle("按销售额正序").param("direction"));
        assertEquals("ASC", parseSingle("按销售额从小到大").param("direction"));
    }

    @Test
    void sortWithTablePrefixAndCombinedWords() {
        ExcelOperation op = parseSingle("把表格按销售额倒序");
        assertEquals(ExcelOperationType.SORT, op.type());
        assertEquals("销售额", op.param("column"));
        assertEquals("DESC", op.param("direction"));
        // 「从小到大排序」连读：方向词后带「排序」也能识别
        assertEquals("销售额", parseSingle("按销售额从小到大排序").param("column"));
        assertEquals("ASC", parseSingle("按销售额从小到大排序").param("direction"));
    }

    // ============================
    // 去重路由
    // ============================
    @Test
    void deduplicateWholeRowPhrasesRouteToDeduplicate() {
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("去重").type());
        assertNull(parseSingle("去重").param("column"));
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复行").type());
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复订单").type());
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复数据").type());
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("去掉重复").type());
    }

    @Test
    void deduplicateByColumnCapturesColumn() {
        ExcelOperation op = parseSingle("按地区去重");
        assertEquals(ExcelOperationType.DEDUPLICATE, op.type());
        assertEquals("地区", op.param("column"));
        assertEquals("地区", parseSingle("按地区列去重").param("column"));
    }

    /** 「删除重复订单」不带第N行，必须走去重而非删除行。 */
    @Test
    void deduplicateDoesNotConflictWithDeleteRow() {
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复订单").type());
        assertEquals(ExcelOperationType.DELETE_ROW, parseSingle("删除第2行").type());
    }

    // ============================
    // 分组汇总路由
    // ============================
    @Test
    void groupSummaryRoutesWithDefaultSum() {
        ExcelOperation op = parseSingle("按地区汇总销售额");
        assertEquals(ExcelOperationType.GROUP_SUMMARY, op.type());
        assertEquals("地区", op.param("groupColumn"));
        assertEquals("销售额", op.param("valueColumn"));
        assertEquals("SUM", op.param("aggregate"));
        assertNull(op.param("includeRatio"));
    }

    @Test
    void groupSummaryAggregateWordsMapToTypes() {
        assertEquals("SUM", parseSingle("按地区统计销售额的合计").param("aggregate"));
        assertEquals("SUM", parseSingle("按地区统计销售额求和").param("aggregate"));
        assertEquals("AVERAGE", parseSingle("按地区统计销售额的平均").param("aggregate"));
        assertEquals("MAX", parseSingle("按地区统计销售额的最大").param("aggregate"));
        assertEquals("MIN", parseSingle("按地区统计销售额的最小").param("aggregate"));
        assertEquals("COUNT", parseSingle("按地区统计订单的数量").param("aggregate"));
        assertEquals("COUNT", parseSingle("按地区统计订单的个数").param("aggregate"));
        assertEquals("COUNT", parseSingle("按地区统计订单的行数").param("aggregate"));
    }

    @Test
    void groupSummaryWithRatioSetsIncludeRatio() {
        ExcelOperation op = parseSingle("按地区汇总销售额并算占比");
        assertEquals(ExcelOperationType.GROUP_SUMMARY, op.type());
        assertEquals("地区", op.param("groupColumn"));
        assertEquals("销售额", op.param("valueColumn"));
        assertEquals("SUM", op.param("aggregate"));
        assertEquals("true", op.param("includeRatio"));
        assertEquals("true", parseSingle("按地区汇总销售额并计算百分比").param("includeRatio"));
    }

    /** 「按X统计Y的合计/行数」这类说法不能被查询分支截胡（列名不能带「按X统计」前缀）。 */
    @Test
    void groupSummaryWithTheAggregateWordRoutesToGroupNotQuery() {
        assertEquals(ExcelOperationType.GROUP_SUMMARY,
            parseSingle("按地区统计销售额的合计").type());
        assertEquals(ExcelOperationType.GROUP_SUMMARY,
            parseSingle("按地区统计人数的行数").type());
        assertEquals(ExcelOperationType.GROUP_SUMMARY,
            parseSingle("按地区统计销售额的平均").type());
    }

    @Test
    void groupSummaryBareRowCountPhraseCountsRows() {
        ExcelOperation op = parseSingle("按地区统计行数");
        assertEquals(ExcelOperationType.GROUP_SUMMARY, op.type());
        assertEquals("地区", op.param("groupColumn"));
        assertEquals("COUNT", op.param("aggregate"));
        assertNull(op.param("valueColumn"));
    }

    // ============================
    // 缺失补全路由
    // ============================
    @Test
    void fillMissingRoutesWithDefaultUnknownValue() {
        ExcelOperation op = parseSingle("补全空白地区");
        assertEquals(ExcelOperationType.FILL_MISSING, op.type());
        assertEquals("地区", op.param("column"));
        assertEquals("未知", op.param("value"));
        assertEquals("地区", parseSingle("补全地区列").param("column"));
        assertEquals("未知", parseSingle("补全地区").param("value"));
    }

    @Test
    void fillMissingWithExplicitValue() {
        ExcelOperation op = parseSingle("把地区补全为0");
        assertEquals(ExcelOperationType.FILL_MISSING, op.type());
        assertEquals("地区", op.param("column"));
        assertEquals("0", op.param("value"));
        assertEquals("未知", parseSingle("把地区列补全为未知").param("value"));
    }

    // ============================
    // 知识管理指令路由
    // ============================
    @Test
    void knowledgeAddFieldMappingRoutesToKnowledgeAdd() {
        ExcelOperation op = parseSingle("添加知识：字段映射 营收→营业收入");
        assertEquals(ExcelOperationType.KNOWLEDGE_ADD, op.type());
        assertEquals("FIELD_MAPPING", op.param("category"));
        assertEquals("营收→营业收入", op.param("content"));
    }

    @Test
    void knowledgeAddBusinessRuleRoutesToKnowledgeAdd() {
        ExcelOperation op = parseSingle("添加知识：业务规则 毛利润=营业收入-营业成本");
        assertEquals(ExcelOperationType.KNOWLEDGE_ADD, op.type());
        assertEquals("BUSINESS_RULE", op.param("category"));
        assertEquals("毛利润=营业收入-营业成本", op.param("content"));
    }

    @Test
    void knowledgeAddWithHelpPrefixAndNoColon() {
        ExcelOperation op = parseSingle("帮我添加知识 操作示例 趋势→折线图");
        assertEquals(ExcelOperationType.KNOWLEDGE_ADD, op.type());
        assertEquals("OPERATION_EXAMPLE", op.param("category"));
        assertEquals("趋势→折线图", op.param("content"));
    }

    @Test
    void knowledgeListPhrasesRouteToKnowledgeList() {
        assertEquals(ExcelOperationType.KNOWLEDGE_LIST, parseSingle("查看知识").type());
        assertEquals(ExcelOperationType.KNOWLEDGE_LIST, parseSingle("查看知识库").type());
        assertEquals(ExcelOperationType.KNOWLEDGE_LIST, parseSingle("请查看知识").type());
    }

    @Test
    void knowledgeDeleteRoutesToKnowledgeDelete() {
        ExcelOperation op = parseSingle("删除知识 营收");
        assertEquals(ExcelOperationType.KNOWLEDGE_DELETE, op.type());
        assertEquals("营收", op.param("keyword"));
    }

    /** 非法类别词保留原文交给校验器提示，不能被当作添加行或识别失败。 */
    @Test
    void knowledgeAddWithUnknownCategoryKeepsRawWord() {
        ExcelOperation op = parseSingle("添加知识：随便 营收→营业收入");
        assertEquals(ExcelOperationType.KNOWLEDGE_ADD, op.type());
        assertEquals("随便", op.param("category"));
        assertEquals("营收→营业收入", op.param("content"));
    }

    /** 知识路由不应改变既有指令（添加行/删除行/去重/查询）的路由结果。 */
    @Test
    void existingRoutesUnchangedByKnowledgeRouting() {
        assertEquals(ExcelOperationType.ADD_ROW, parseSingle("添加一行：张三,25,北京").type());
        assertEquals(ExcelOperationType.DELETE_ROW, parseSingle("删除第2行").type());
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复订单").type());
        assertEquals(ExcelOperationType.QUERY, parseSingle("统计人数的多少行").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("查看版本历史").type());
    }

    // ============================
    // 版本历史与无法识别
    // ============================
    @Test
    void versionHistoryPhrasesRouteToVersionHistory() {
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("查看版本").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("历史版本").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("请版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("帮我查看版本").type());
    }

    /** 前缀「请/帮我」任意组合与重复均可识别，且「查看版本历史」连读也可识别。 */
    @Test
    void versionHistoryWithCombinedHelpPrefixes() {
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("请帮我查看版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("查看版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("帮我 版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("请帮我 查看版本").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("请请帮我帮我版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("帮我查看版本历史记录").type());
    }

    @Test
    void unrecognizedTextReturnsNull() {
        assertNull(parser.parse("user-1", "你好"));
        assertNull(parser.parse("user-1", "随便说点什么"));
    }

    /** 分析类路由不应改变既有指令（生成/加行/查询/删除/回滚/版本历史）的路由结果。 */
    @Test
    void existingRoutesUnchangedByAnalysisRouting() {
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("撤销删除第2行").type());
        assertEquals(ExcelOperationType.QUERY, parseSingle("查询金额的最大值").type());
        assertEquals(ExcelOperationType.QUERY, parseSingle("合计金额").type());
        assertEquals(ExcelOperationType.QUERY, parseSingle("统计人数的多少行").type());
        assertEquals(ExcelOperationType.DELETE_ROW, parseSingle("删除第2行").type());
        assertEquals(ExcelOperationType.UPDATE_ROW, parseSingle("修改第2行为 张三,25,北京").type());
        assertEquals(ExcelOperationType.ADD_ROW, parseSingle("添加一行：张三,25,北京").type());
        assertEquals(ExcelOperationType.CREATE_TABLE, parseSingle("生成表格：姓名,城市").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("查看版本历史").type());
    }

    // ============================
    // 复合任务：安全切分与线性依赖链
    // ============================
    @Test
    void compositeSplitsDeduplicateThenFillMissing() {
        ExcelPlan plan = parser.parse("user-1", "删除重复订单，补全空白地区");
        assertNotNull(plan);
        assertEquals(2, plan.operations().size());
        ExcelOperation first = plan.operations().get(0);
        ExcelOperation second = plan.operations().get(1);
        assertEquals("1", first.id());
        assertEquals(ExcelOperationType.DEDUPLICATE, first.type());
        assertEquals("2", second.id());
        assertEquals(ExcelOperationType.FILL_MISSING, second.type());
        assertEquals("地区", second.param("column"));
        // 线性依赖链：第 2 步依赖第 1 步
        assertEquals(List.of(), first.dependsOn());
        assertEquals(List.of("1"), second.dependsOn());
    }

    /** 整段无法命中单操作（尾部还有「再按销售额倒序」）：切为 分组汇总(含占比) → 排序。 */
    @Test
    void compositeGroupSummaryWithRatioThenSort() {
        ExcelPlan plan = parser.parse("user-1", "按地区汇总销售额并计算占比，再按销售额倒序");
        assertNotNull(plan);
        assertEquals(2, plan.operations().size());
        ExcelOperation first = plan.operations().get(0);
        assertEquals(ExcelOperationType.GROUP_SUMMARY, first.type());
        assertEquals("地区", first.param("groupColumn"));
        assertEquals("销售额", first.param("valueColumn"));
        assertEquals("true", first.param("includeRatio"));
        ExcelOperation second = plan.operations().get(1);
        assertEquals(ExcelOperationType.SORT, second.type());
        assertEquals("销售额", second.param("column"));
        assertEquals("DESC", second.param("direction"));
        assertEquals(List.of("1"), second.dependsOn());
    }

    @Test
    void compositeSplitsThreeOperationsInOrder() {
        ExcelPlan plan = parser.parse("user-1", "删除重复订单，补全空白地区，再按销售额倒序");
        assertNotNull(plan);
        assertEquals(3, plan.operations().size());
        assertEquals(ExcelOperationType.DEDUPLICATE, plan.operations().get(0).type());
        assertEquals(ExcelOperationType.FILL_MISSING, plan.operations().get(1).type());
        ExcelOperation sort = plan.operations().get(2);
        assertEquals(ExcelOperationType.SORT, sort.type());
        // 线性依赖链依次串联
        assertEquals(List.of("1"), plan.operations().get(1).dependsOn());
        assertEquals(List.of("2"), sort.dependsOn());
    }

    /** 连接词「然后」作切分点：切在连接词末尾，片段尾部的残留标点被清理。 */
    @Test
    void compositeWithConnectiveSeparator() {
        ExcelPlan plan = parser.parse("user-1", "删除重复订单，然后按年龄排序");
        assertNotNull(plan);
        assertEquals(2, plan.operations().size());
        assertEquals(ExcelOperationType.DEDUPLICATE, plan.operations().get(0).type());
        ExcelOperation sort = plan.operations().get(1);
        assertEquals(ExcelOperationType.SORT, sort.type());
        assertEquals("年龄", sort.param("column"));
        assertEquals("ASC", sort.param("direction"));
    }

    /** 多行文本是表格数据形态：复合切分直接让位，仍按原路由生成表格。 */
    @Test
    void multiLineTextIsNeverSplit() {
        ExcelPlan plan = parser.parse("user-1", "删除重复订单\n补全空白地区");
        assertNotNull(plan);
        assertEquals(1, plan.operations().size());
        assertEquals(ExcelOperationType.CREATE_TABLE, plan.operations().get(0).type());
    }

    /** 单操作文本仍产出单操作：复合路由不改变既有分析指令（回归）。 */
    @Test
    void singleAnalysisOperationRemainsSingle() {
        assertEquals(ExcelOperationType.SORT, parseSingle("按销售额倒序").type());
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复订单").type());
        assertEquals(ExcelOperationType.GROUP_SUMMARY, parseSingle("按地区汇总销售额").type());
        assertEquals(ExcelOperationType.FILL_MISSING, parseSingle("补全空白地区").type());
    }

    /** 「并」后是占比（非操作开头词）：不切分，仍为单个 GROUP_SUMMARY（含占比）。 */
    @Test
    void ratioPhraseIsNotSplitFromGroupSummary() {
        ExcelOperation op = parseSingle("按地区汇总销售额并计算占比");
        assertEquals(ExcelOperationType.GROUP_SUMMARY, op.type());
        assertEquals("true", op.param("includeRatio"));
    }

    /** 含非分析片段（添加一行）时整个复合让位：仍按原路由命中添加行。 */
    @Test
    void compositeWithNonAnalysisFragmentFallsBackToAddRow() {
        ExcelPlan plan = parser.parse("user-1", "添加一行：张三,25，然后按年龄排序");
        assertNotNull(plan);
        assertEquals(1, plan.operations().size());
        assertEquals(ExcelOperationType.ADD_ROW, plan.operations().get(0).type());
    }

    // ============================
    // 工作簿管理指令路由（多工作簿）
    // ============================
    @Test
    void workbookCreateRoutesToWorkbookCreate() {
        ExcelOperation op = parseSingle("新建表格 销售表");
        assertEquals(ExcelOperationType.WORKBOOK_CREATE, op.type());
        assertEquals("销售表", op.param("title"));
        assertEquals("周报", parseSingle("新建工作簿 周报").param("title"));
        assertEquals("销售表", parseSingle("新建表格名字 销售表").param("title"));
        assertEquals("销售表", parseSingle("请新建表格：销售表").param("title"));
    }

    /** 「新建表格 X」带表格数据（换行/分隔符）时仍是生成表格，名称不能成为工作簿标题。 */
    @Test
    void workbookCreateWithTableDataStaysCreateTable() {
        ExcelOperation op = parseSingle("新建表格：姓名,城市\n张三,北京");
        assertEquals(ExcelOperationType.CREATE_TABLE, op.type());
        assertEquals("姓名,城市", op.param("headers"));
        assertEquals("张三,北京", op.param("rows"));
        assertEquals(ExcelOperationType.CREATE_TABLE, parseSingle("新建表格：姓名,城市").type());
    }

    @Test
    void workbookListPhrasesRouteToWorkbookList() {
        assertEquals(ExcelOperationType.WORKBOOK_LIST, parseSingle("我的表格").type());
        assertEquals(ExcelOperationType.WORKBOOK_LIST, parseSingle("表格列表").type());
        assertEquals(ExcelOperationType.WORKBOOK_LIST, parseSingle("查看表格列表").type());
        assertEquals(ExcelOperationType.WORKBOOK_LIST, parseSingle("有哪些表格").type());
    }

    @Test
    void workbookSelectRoutesToWorkbookSelect() {
        ExcelOperation op = parseSingle("选择表格 销售表");
        assertEquals(ExcelOperationType.WORKBOOK_SELECT, op.type());
        assertEquals("销售表", op.param("name"));
        assertEquals("销售表", parseSingle("切换到表格 销售表").param("name"));
        assertEquals("销售表", parseSingle("打开表格 销售表").param("name"));
    }

    @Test
    void workbookRenameRoutesToWorkbookRename() {
        ExcelOperation op = parseSingle("重命名表格 销售表为月度销售");
        assertEquals(ExcelOperationType.WORKBOOK_RENAME, op.type());
        assertEquals("销售表", op.param("name"));
        assertEquals("月度销售", op.param("newTitle"));
        ExcelOperation prefixed = parseSingle("把表格销售表改名为月度销售");
        assertEquals(ExcelOperationType.WORKBOOK_RENAME, prefixed.type());
        assertEquals("销售表", prefixed.param("name"));
        assertEquals("月度销售", prefixed.param("newTitle"));
    }

    @Test
    void workbookDeleteRoutesToWorkbookDelete() {
        ExcelOperation op = parseSingle("删除表格 销售表");
        assertEquals(ExcelOperationType.WORKBOOK_DELETE, op.type());
        assertEquals("销售表", op.param("name"));
        assertEquals("销售表", parseSingle("删除工作簿 销售表").param("name"));
    }

    @Test
    void workbookCopyRoutesToWorkbookCopy() {
        ExcelOperation op = parseSingle("复制表格 销售表");
        assertEquals(ExcelOperationType.WORKBOOK_COPY, op.type());
        assertEquals("销售表", op.param("name"));
        assertEquals("销售表", parseSingle("复制工作簿 销售表").param("name"));
    }

    /** 工作簿管理路由不应改变既有指令的路由结果（生成/加行/删除行/去重/知识/版本历史/回滚）。 */
    @Test
    void existingRoutesUnchangedByWorkbookRouting() {
        assertEquals(ExcelOperationType.CREATE_TABLE, parseSingle("生成表格：姓名,城市").type());
        assertEquals(ExcelOperationType.ADD_ROW, parseSingle("添加一行：张三,25,北京").type());
        assertEquals(ExcelOperationType.DELETE_ROW, parseSingle("删除第2行").type());
        assertEquals(ExcelOperationType.DEDUPLICATE, parseSingle("删除重复订单").type());
        assertEquals(ExcelOperationType.KNOWLEDGE_ADD,
            parseSingle("添加知识：字段映射 营收→营业收入").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("查看版本历史").type());
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("撤销").type());
    }

    // ============================
    // 操作日志 / 版本对比路由
    // ============================
    @Test
    void auditListCommandsRouteToAuditList() {
        assertEquals(ExcelOperationType.AUDIT_LIST, parseSingle("查看操作日志").type());
        assertEquals(ExcelOperationType.AUDIT_LIST, parseSingle("操作历史").type());
        assertEquals(ExcelOperationType.AUDIT_LIST, parseSingle("请查看操作日志").type());
        assertEquals(ExcelOperationType.AUDIT_LIST, parseSingle("帮我查看操作日志").type());
    }

    @Test
    void versionDiffCommandsRouteToVersionDiff() {
        assertEquals(ExcelOperationType.VERSION_DIFF, parseSingle("版本对比").type());
        assertEquals(ExcelOperationType.VERSION_DIFF, parseSingle("对比上一版").type());
        assertEquals(ExcelOperationType.VERSION_DIFF, parseSingle("对比上一版本").type());
        assertEquals(ExcelOperationType.VERSION_DIFF, parseSingle("请对比上一版").type());
    }

    /** 新路由不应改变既有指令的路由结果（版本历史/操作日志/生成互不混淆）。 */
    @Test
    void existingRoutesUnchangedByAuditAndDiffRouting() {
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("查看版本历史").type());
        assertEquals(ExcelOperationType.VERSION_HISTORY, parseSingle("版本历史").type());
        assertEquals(ExcelOperationType.ROLLBACK, parseSingle("恢复").type());
        assertEquals(ExcelOperationType.CREATE_TABLE, parseSingle("生成表格：姓名,城市").type());
    }

    // ============================
    // 表格式化指令路由（加标题/冻结首行/加筛选/美化表格）
    // ============================
    @Test
    void formatTitlePhrasesRouteToFormatTable() {
        ExcelOperation op = parseSingle("加标题 销售报表");
        assertEquals(ExcelOperationType.FORMAT_TABLE, op.type());
        assertEquals("销售报表", op.param("title"));
        assertNull(op.param("freezeHeader"));
        assertEquals("销售报表", parseSingle("标题为 销售报表").param("title"));
        assertEquals("销售报表", parseSingle("设置标题 销售报表").param("title"));
        assertEquals("销售报表", parseSingle("请设置标题：销售报表").param("title"));
    }

    @Test
    void formatFreezeAndFilterPhrasesRouteToFormatTable() {
        assertEquals("true", parseSingle("冻结首行").param("freezeHeader"));
        assertEquals("true", parseSingle("冻结表头").param("freezeHeader"));
        assertEquals("true", parseSingle("加筛选").param("autoFilter"));
        assertEquals("true", parseSingle("自动筛选").param("autoFilter"));
    }

    /** 美化表格/格式化：全部默认（无标题，冻结 + 筛选开）。 */
    @Test
    void formatBeautifyDefaultsToFreezeAndFilter() {
        ExcelOperation op = parseSingle("美化表格");
        assertEquals(ExcelOperationType.FORMAT_TABLE, op.type());
        assertEquals("true", op.param("freezeHeader"));
        assertEquals("true", op.param("autoFilter"));
        assertNull(op.param("title"));
        ExcelOperation generic = parseSingle("格式化");
        assertEquals("true", generic.param("freezeHeader"));
        assertEquals("true", generic.param("autoFilter"));
    }

    /** 可组合指令：整句多关键词识别，产出单个 FORMAT_TABLE 操作。 */
    @Test
    void formatCombinedKeywordsProduceSingleOperation() {
        ExcelOperation op = parseSingle("加标题 销售报表，冻结首行，加筛选");
        assertEquals(ExcelOperationType.FORMAT_TABLE, op.type());
        assertEquals("销售报表", op.param("title"));
        assertEquals("true", op.param("freezeHeader"));
        assertEquals("true", op.param("autoFilter"));
    }

    // ============================
    // 图表指令路由（柱状/折线/饼图，三种形态）
    // ============================
    @Test
    void chartColonFormRoutesToChartWithColumns() {
        ExcelOperation op = parseSingle("生成柱状图：产品名称,销售额");
        assertEquals(ExcelOperationType.CHART, op.type());
        assertEquals("BAR", op.param("chartType"));
        assertEquals("产品名称", op.param("categoryColumn"));
        assertEquals("销售额", op.param("valueColumn"));
    }

    @Test
    void chartTypeWordsMapToBarLinePie() {
        assertEquals("BAR", parseSingle("生成柱形图：产品名称,销售额").param("chartType"));
        assertEquals("LINE", parseSingle("折线图 日期,价格").param("chartType"));
        assertEquals("PIE", parseSingle("饼图：地区,销售额").param("chartType"));
    }

    @Test
    void chartByPhrasesRouteToChart() {
        ExcelOperation paren = parseSingle("按产品名称生成柱状图（销售额）");
        assertEquals(ExcelOperationType.CHART, paren.type());
        assertEquals("BAR", paren.param("chartType"));
        assertEquals("产品名称", paren.param("categoryColumn"));
        assertEquals("销售额", paren.param("valueColumn"));
        ExcelOperation joined = parseSingle("按产品名称生成销售额柱状图");
        assertEquals(ExcelOperationType.CHART, joined.type());
        assertEquals("BAR", joined.param("chartType"));
        assertEquals("产品名称", joined.param("categoryColumn"));
        assertEquals("销售额", joined.param("valueColumn"));
        assertEquals("LINE", parseSingle("按日期生成折线图（价格）").param("chartType"));
        assertEquals("PIE", parseSingle("按地区生成销售额饼图").param("chartType"));
    }

    /** 图型词后只有分类列时：数值列参数不产出，交给校验器提示。 */
    @Test
    void chartMissingValueColumnIsOmittedForValidator() {
        ExcelOperation op = parseSingle("生成柱状图：产品名称");
        assertEquals(ExcelOperationType.CHART, op.type());
        assertEquals("产品名称", op.param("categoryColumn"));
        assertNull(op.param("valueColumn"));
    }

    // ============================
    // 汇总页指令路由
    // ============================
    @Test
    void dashboardCommandsRouteToDashboard() {
        assertEquals(ExcelOperationType.DASHBOARD, parseSingle("生成汇总页").type());
        assertEquals(ExcelOperationType.DASHBOARD, parseSingle("汇总页").type());
        assertEquals(ExcelOperationType.DASHBOARD, parseSingle("dashboard").type());
        assertEquals(ExcelOperationType.DASHBOARD, parseSingle("Dashboard").type());
        assertEquals(ExcelOperationType.DASHBOARD, parseSingle("请生成汇总页").type());
    }

    /** 「加标题」不被添加行路由吞掉；「生成柱状图/生成汇总页」不被生成表格路由吞掉。 */
    @Test
    void formatChartDashboardRouteBeforeAddAndCreate() {
        assertEquals(ExcelOperationType.FORMAT_TABLE, parseSingle("加标题 销售报表").type());
        assertEquals(ExcelOperationType.FORMAT_TABLE, parseSingle("加筛选").type());
        assertEquals(ExcelOperationType.CHART, parseSingle("生成柱状图：产品名称,销售额").type());
        assertEquals(ExcelOperationType.DASHBOARD, parseSingle("生成汇总页").type());
    }

    /** 导出指令：导出/下载表格（可带表名）路由到 EXPORT。 */
    @Test
    void exportCommandsRouteToExport() {
        assertEquals(ExcelOperationType.EXPORT, parseSingle("导出表格").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("下载表格").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("导出").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("请帮我导出表格").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("导出“季度销售”表格").type());
    }

    /** 导出指令：把X表格发给我/给我表格文件/发我表格 等口语说法路由到 EXPORT。 */
    @Test
    void exportColloquialPhrasesRouteToExport() {
        assertEquals(ExcelOperationType.EXPORT, parseSingle("把表格发给我").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("把“季度销售”表格发给我").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("给我表格文件").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("给我表格").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("发我表格").type());
        assertEquals(ExcelOperationType.EXPORT, parseSingle("发送表格").type());
    }

    /** 导出路由不应吞掉「把表格按X排序/把表格X改名」等既有指令。 */
    @Test
    void exportRoutingDoesNotHijackExistingCommands() {
        assertEquals(ExcelOperationType.SORT, parseSingle("把表格按销售额倒序").type());
        assertEquals(ExcelOperationType.WORKBOOK_RENAME,
            parseSingle("把表格销售表改名为月度销售").type());
    }

    /** 新路由不应改变既有指令的路由结果（添加行/生成/排序/工作簿/知识）。 */
    @Test
    void existingRoutesUnchangedByFormatChartDashboardRouting() {
        assertEquals(ExcelOperationType.ADD_ROW, parseSingle("添加一行：张三,25,北京").type());
        assertEquals(ExcelOperationType.CREATE_TABLE, parseSingle("生成表格：姓名,城市").type());
        assertEquals(ExcelOperationType.SORT, parseSingle("按销售额排序").type());
        assertEquals(ExcelOperationType.WORKBOOK_CREATE, parseSingle("新建表格 销售表").type());
        assertEquals(ExcelOperationType.KNOWLEDGE_ADD,
            parseSingle("添加知识：字段映射 营收→营业收入").type());
        assertEquals(ExcelOperationType.WORKBOOK_RENAME,
            parseSingle("重命名表格 销售表为月度销售").type());
    }
}
