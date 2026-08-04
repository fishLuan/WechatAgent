package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import org.junit.jupiter.api.Test;

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
}
