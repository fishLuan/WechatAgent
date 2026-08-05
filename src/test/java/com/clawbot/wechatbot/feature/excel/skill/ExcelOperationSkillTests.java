package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelAuditLog;
import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import com.clawbot.wechatbot.feature.excel.service.ExcelAuditService;
import com.clawbot.wechatbot.feature.excel.service.ExcelRagService;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Excel 操作技能测试：防静默覆盖保护、版本回滚、版本历史。 */
class ExcelOperationSkillTests {

    private final SkillDefinition definition = mock(SkillDefinition.class);

    @Test
    void refusesToOverwriteExistingTableWithoutCoverKeyword() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1")))
            .thenReturn(existingTable());
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("覆盖"));
        verify(excelService, never()).save(any());
    }

    @Test
    void blockedMessageShowsCorrectOverwriteExample() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1")))
            .thenReturn(existingTable());
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("生成覆盖表格"));
        assertTrue(result.text().contains("张三,北京"));
        verify(excelService, never()).save(any());
    }

    @Test
    void overwriteWithMultiLineDataAfterColonKeepsAllRows() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1",
                "生成覆盖表格：姓名,城市\n张三,北京\n李四,上海", "", "", ""));

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
        assertEquals(List.of(List.of("张三", "北京"), List.of("李四", "上海")),
            table.getRows());
        verify(excelService).save(table);
    }

    @Test
    void plainPasteWithColonInDataIsNotChopped() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1",
                "姓名,城市,备注\n张三,北京,时间：9点", "", "", ""));

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市", "备注"), table.getHeaders());
        assertEquals(List.of(List.of("张三", "北京", "时间：9点")), table.getRows());
        verify(excelService).save(table);
    }

    @Test
    void coverKeywordInsideDataDoesNotUnlockOverwrite() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1")))
            .thenReturn(existingTable());
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1",
                "生成表格：姓名,城市\n张三,覆盖区域", "", "", ""));

        assertFalse(result.success());
        verify(excelService, never()).save(any());
    }

    @Test
    void createTableExportFailureDoesNotPersist() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenThrow(
            new IllegalArgumentException("❌ 公式存在错误，已取消导出：单元格 B1 为 #DIV/0!。"));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "姓名,数值\n张三,=1/0", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("公式存在错误"));
        // 导出失败不得落库：save 不应被调用，表格保持原状态
        verify(excelService, never()).save(any());
    }

    @Test
    void addRowExportFailureDoesNotPersist() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenThrow(
            new IllegalArgumentException("❌ 公式存在错误，已取消导出：单元格 B2 为 #DIV/0!。"));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "添加一行：王五,=1/0", "", "", ""));

        assertFalse(result.success());
        verify(excelService, never()).save(any());
    }

    @Test
    void overwritesExistingTableWhenInstructionContainsCover() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成覆盖表格：姓名,城市", "", "", ""));

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
        verify(excelService).save(table);
    }

    @Test
    void overwritesEmptyTableWithoutCoverKeyword() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市", "", "", ""));

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
        verify(excelService).save(table);
    }

    // ============================
    // 标题：首次创建应用指令标题，覆盖已有数据时保留原标题
    // ============================
    @Test
    void createTableAppliesInstructionTitleOnFirstCreate() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成销售表：姓名,城市", "", "", ""));

        assertTrue(result.success());
        // 首次创建（还没有数据）时，指令里的「销售表」成为表格标题
        assertEquals("销售表", table.getTitle());
        verify(excelService).save(table);
    }

    @Test
    void overwriteKeepsOriginalTitle() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();   // 标题「旧表」且已有数据
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成覆盖表格：姓名,城市", "", "", ""));

        assertTrue(result.success());
        // 覆盖已有数据时保留原标题，不被指令里的默认标题顶掉
        assertEquals("旧表", table.getTitle());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
    }

    /** 已有非空数据的表格。 */
    private ExcelTable existingTable() {
        ExcelTable table = new ExcelTable("user-1", "旧表");
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(List.of(List.of("李四", "30")));
        return table;
    }

    // ============================
    // 版本回滚与版本历史
    // ============================
    @Test
    void rollbackWithoutVersionsReturnsFailure() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(0L);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "回滚", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("没有可回滚"));
        // 没有版本时不写回滚快照，也不保存表格
        verify(excelService, never()).snapshotVersion(any(), anyString());
        verify(excelService, never()).save(any());
    }

    @Test
    void rollbackRestoresLatestVersionAndExportsAttachment() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(1L);
        ExcelTableVersion restored = new ExcelTableVersion(
            "t1", List.of("姓名"), List.of(), "添加第1行");
        when(excelService.restoreLatestVersion(table)).thenReturn(restored);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "回滚", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已回滚到上一版本"));
        // 回滚前先对当前状态快照（「回滚操作」），再恢复最新版本并保存
        verify(excelService).snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        verify(excelService).restoreLatestVersion(table);
        verify(excelService).save(table);
        verify(excelService).consumeVersion(restored);
    }

    @Test
    void rollbackAliasesUndoAndRestoreDispatch() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(1L);
        ExcelTableVersion restored = new ExcelTableVersion(
            "t1", List.of("姓名"), List.of(), "添加第1行");
        when(excelService.restoreLatestVersion(table)).thenReturn(restored);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        assertTrue(skill.execute(definition,
            new SkillRequest("user-1", "撤销", "", "", "")).success());
        verify(excelService, atLeastOnce()).restoreLatestVersion(table);

        assertTrue(skill.execute(definition,
            new SkillRequest("user-1", "恢复", "", "", "")).success());
        verify(excelService, atLeastOnce()).snapshotVersion(table, ExcelService.ROLLBACK_DESCRIPTION);
        verify(excelService, atLeastOnce()).consumeVersion(restored);

        assertTrue(skill.execute(definition,
            new SkillRequest("user-1", "请帮我回滚", "", "", "")).success());
        verify(excelService, atLeastOnce()).restoreLatestVersion(table);
    }

    @Test
    void createTableMultiLineInstructionUsesCleanTitle() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1",
                "生成销售表：姓名,城市\n张三,北京", "", "", ""));

        assertTrue(result.success());
        assertEquals("销售表", table.getTitle());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
        assertEquals(List.of(List.of("张三", "北京")), table.getRows());
    }

    @Test
    void undoPhraseWithDeleteDescriptionRoutesToRollbackNotDelete() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(1L);
        ExcelTableVersion restored = new ExcelTableVersion(
            "t1", List.of("姓名"), List.of(), "添加第1行");
        when(excelService.restoreLatestVersion(table)).thenReturn(restored);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "撤销删除第2行", "", "", ""));

        assertTrue(result.success());
        // 「撤销…」必须走回滚，而不是真的删除第2行
        verify(excelService).restoreLatestVersion(table);
        assertEquals(1, table.getRows().size());
    }

    @Test
    void rollbackExportFailureDoesNotConsumeVersion() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(1L);
        ExcelTableVersion restored = new ExcelTableVersion(
            "t1", List.of("姓名"), List.of(), "添加第1行");
        when(excelService.restoreLatestVersion(table)).thenReturn(restored);
        when(excelService.toXlsx(any())).thenThrow(
            new IllegalArgumentException("❌ 公式存在错误，已取消导出。"));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "回滚", "", "", ""));

        assertFalse(result.success());
        // 导出失败：不落库、不消费版本，重试仍可回到同一目标
        verify(excelService, never()).save(any());
        verify(excelService, never()).consumeVersion(any());
    }

    @Test
    void versionHistoryReportsCountAndRecentDescriptions() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(2L);
        ExcelTableVersion v1 = new ExcelTableVersion(
            "t1", List.of("姓名"), List.of(), "添加第1行");
        ExcelTableVersion v2 = new ExcelTableVersion(
            "t1", List.of("姓名"), List.of(), "修改第2行");
        when(excelService.recentVersions(table, 5)).thenReturn(List.of(v2, v1));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "版本历史", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("2 条"));
        assertTrue(result.text().contains("添加第1行"));
        assertTrue(result.text().contains("修改第2行"));
    }

    @Test
    void versionHistoryWithoutVersionsReturnsHint() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(0L);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查看版本", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("还没有版本记录"));
    }

    // ============================
    // 分析类操作（排序/去重/分组汇总/缺失补全）端到端
    // ============================
    @Test
    void sortInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("李四", "30"), List.of("张三", "25"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "按年龄倒序", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已按年龄排序"));
        assertTrue(result.text().contains("降序"));
        // 降序：30 排在 25 前
        assertEquals(List.of(List.of("李四", "30"), List.of("张三", "25")), table.getRows());
        verify(excelService).save(table);
    }

    @Test
    void deduplicateInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setRows(new ArrayList<>(List.of(
            List.of("李四", "30"), List.of("李四", "30"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "删除重复订单", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已删除 1 行重复数据"));
        verify(excelService).save(table);
    }

    @Test
    void groupSummaryInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("上海", "200"), List.of("北京", "300"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "按地区汇总销售额并算占比", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已生成汇总表，原表已替换，可回滚"));
        assertEquals(List.of("地区", "销售额(合计)", "占比"), table.getHeaders());
        verify(excelService).save(table);
    }

    @Test
    void fillMissingInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setHeaders(List.of("姓名", "年龄"));
        table.setRows(new ArrayList<>(List.of(
            new ArrayList<>(List.of("李四", "")), new ArrayList<>(List.of("张三", "25")))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "补全空白年龄", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已补全年龄列 1 个空值"));
        assertEquals(List.of(List.of("李四", "未知"), List.of("张三", "25")), table.getRows());
        verify(excelService).save(table);
    }

    @Test
    void analysisOperationWithUnknownColumnFailsValidation() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "按不存在的列排序", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("找不到列"));
        verify(excelService, never()).save(any());
    }

    // ============================
    // 复合任务端到端：多步串联、汇总文案
    // ============================
    @Test
    void compositeInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("北京", "100"), List.of("上海", "200"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "删除重复订单，然后按地区汇总销售额", "", "", ""));

        assertTrue(result.success());
        // 复合回复：已完成 N 步 + 最后一步文案
        assertTrue(result.text().contains("已完成 2 步操作"));
        assertTrue(result.text().contains("最后一步：已生成汇总表"));
        // 两步均生效：先去重（北京 100 重复一次），再按地区汇总
        assertEquals(List.of("地区", "销售额(合计)"), table.getHeaders());
        assertEquals(List.of(List.of("北京", "100.00"), List.of("上海", "200.00")),
            table.getRows());
        verify(excelService, atLeastOnce()).save(table);
    }

    /** 兜底文案应包含新增的四种分析类操作说明。 */
    @Test
    void fallbackMessageListsAnalysisOperations() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "你好", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("排序"));
        assertTrue(result.text().contains("去重"));
        assertTrue(result.text().contains("汇总"));
        assertTrue(result.text().contains("补全"));
    }

    // ============================
    // 知识管理指令端到端（RAG 版双参数构造器）
    // ============================
    @Test
    void knowledgeAddInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        ExcelRagService ragService = mock(ExcelRagService.class);
        when(ragService.upsert(anyString(), anyList(), any(), any(), any()))
            .thenReturn(new ExcelRagService.AddResult(null, false));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, ragService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "添加知识：字段映射 营业额→营业收入", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已添加知识"));
        assertTrue(result.text().contains("字段映射"));
        assertTrue(result.text().contains("营业额→营业收入"));
        // 纯文字回复，不导出附件、不保存表格
        assertTrue(result.attachments().isEmpty());
        verify(ragService).upsert(eq("FIELD_MAPPING"), anyList(), eq("营业收入"), isNull(), isNull());
        verify(excelService, never()).save(any());
    }

    @Test
    void knowledgeListInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        ExcelRagService ragService = mock(ExcelRagService.class);
        ExcelRagKnowledge rule = new ExcelRagKnowledge("BUSINESS_RULE",
            List.of("毛利", "毛利润"), null, "毛利润 = 营业收入 - 营业成本", null);
        when(ragService.list(10)).thenReturn(List.of(rule));
        when(ragService.count()).thenReturn(1L);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, ragService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查看知识", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("共 1 条"));
        assertTrue(result.text().contains("毛利润"));
        assertTrue(result.attachments().isEmpty());
    }

    @Test
    void knowledgeDeleteInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        ExcelRagService ragService = mock(ExcelRagService.class);
        when(ragService.deleteByKeyword("营收")).thenReturn(true);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, ragService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "删除知识 营收", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已删除"));
        verify(ragService).deleteByKeyword("营收");
    }

    @Test
    void knowledgeDeleteWithoutMatchReturnsFailure() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        ExcelRagService ragService = mock(ExcelRagService.class);
        when(ragService.deleteByKeyword("不存在")).thenReturn(false);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, ragService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "删除知识 不存在", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("未找到"));
    }

    // ============================
    // 知识库别名解析端到端
    // ============================
    /** 表内模糊匹配命中不了的别名（营业额 → 营业收入）：按知识库替换列名并在回复中标注。 */
    @Test
    void aliasResolutionEndToEndWithRagService() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setHeaders(List.of("营业收入", "地区"));
        table.setRows(new ArrayList<>(List.of(List.of("100", "北京"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.queryColumn(eq(table), eq("营业收入"), eq(ExcelService.QueryType.SUM)))
            .thenReturn("📊 营业收入列的合计：100.00（基于 1 个数值）");
        ExcelRagService ragService = mock(ExcelRagService.class);
        when(ragService.resolveColumnAlias("营业额")).thenReturn("营业收入");
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, ragService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "统计营业额", "", "", ""));

        assertTrue(result.success());
        // 回复含知识库映射标注，且查询按真实列名执行
        assertTrue(result.text().contains("📚 已按知识库将「营业额」映射为「营业收入」"));
        assertTrue(result.text().contains("营业收入列的合计"));
        verify(excelService).queryColumn(eq(table), eq("营业收入"), eq(ExcelService.QueryType.SUM));
    }

    /** 命中的业务规则在成功回复前加注（知识库标注）。 */
    @Test
    void ruleKnowledgeIsAnnotatedOnSuccessReply() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setHeaders(List.of("当前库存", "安全库存"));
        table.setRows(new ArrayList<>(List.of(List.of("10", "20"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.queryColumn(eq(table), eq("当前库存"), eq(ExcelService.QueryType.MAX)))
            .thenReturn("📊 当前库存列的最大值：10（基于 1 个数值）");
        ExcelRagService ragService = mock(ExcelRagService.class);
        ExcelRagKnowledge rule = new ExcelRagKnowledge("BUSINESS_RULE",
            List.of("库存", "预警"), null, "库存预警：当前库存小于安全库存时标红", null);
        when(ragService.findRules(anyString())).thenReturn(List.of(rule));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, ragService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查询当前库存的最大值", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("📚 知识库规则：库存预警：当前库存小于安全库存时标红"));
        assertTrue(result.text().contains("当前库存列的最大值"));
    }

    /** 单参数构造器（无 RAG）：别名解析跳过，查询仍按原别名执行（回归）。 */
    @Test
    void singleArgConstructorWithoutRagKeepsBehaviorUnchanged() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.queryColumn(any(), eq("营业额"), any()))
            .thenReturn("❌ 找不到列「营业额」，现有列：姓名、年龄");
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "统计营业额", "", "", ""));

        // 无知识库：不解析别名，按原别名执行查询（找不到列是既有行为）
        assertTrue(result.text().contains("找不到列"));
        verify(excelService).queryColumn(eq(table), eq("营业额"), eq(ExcelService.QueryType.SUM));
    }

    // ============================
    // 多工作簿：无活动表报错、工作簿管理指令、新建后旧指令作用于新表
    // ============================
    /** 没有活动表时，旧指令（生成/增删改行/分析等）给出明确报错，不落库。 */
    @Test
    void nonCreateInstructionWithoutActiveWorkbookReturnsClearHint() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "添加行：张三,北京", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("还没有表格，请先发送「新建表格 名字」创建"));
        assertTrue(result.text().contains("上传 xlsx"));
        verify(excelService, never()).save(any());
    }

    /** 首次使用（无活动表）：生成/创建表格应先建空表再填充，不再被无活动表拦截。 */
    @Test
    void firstCreateWithoutActiveWorkbookCreatesTable() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(null);
        ExcelTable fresh = new ExcelTable("user-1", "表格");
        when(excelService.createWorkbook(eq("user-1"), anyString())).thenReturn(fresh);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市\n张三,北京", "", "", ""));

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市"), fresh.getHeaders());
        assertEquals(List.of(List.of("张三", "北京")), fresh.getRows());
        verify(excelService).createWorkbook(eq("user-1"), anyString());
        verify(excelService).save(fresh);
    }

    /** 首次使用 + 「创建名为X的表格，表头为…」说法：标题取 X，表头正确落入新表。 */
    @Test
    void firstCreateWithNamedPhraseAppliesTitleAndHeaders() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(null);
        ExcelTable fresh = new ExcelTable("user-1", "表格");
        when(excelService.createWorkbook(eq("user-1"), anyString())).thenReturn(fresh);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1",
                "创建名为“季度销售”的Excel表格，表头为：产品,数量,金额"
                    + "（首行为表头，每行一条数据）", "", "", ""));

        assertTrue(result.success());
        assertEquals("季度销售", fresh.getTitle());
        assertEquals(List.of("产品", "数量", "金额"), fresh.getHeaders());
        assertEquals(1, result.attachments().size());
        verify(excelService).createWorkbook(eq("user-1"), eq("季度销售"));
    }

    /** 方案一：添加行等内容操作只回文字，不再附带 xlsx（需要完整文件时发「导出表格」）。 */
    @Test
    void contentOperationsDoNotAttachFile() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(existingTable());
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "添加行：张三,北京", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已添加"));
        assertTrue(result.attachments().isEmpty());
        verify(excelService).toXlsx(any());
    }

    /** 导出表格：带附件返回当前表完整文件，不修改数据、不落库。 */
    @Test
    void exportInstructionAttachesCurrentTable() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "导出表格", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已导出"));
        assertTrue(result.text().contains("2列×1行"));
        assertEquals(1, result.attachments().size());
        verify(excelService).toXlsx(table);
        verify(excelService, never()).save(any());
    }

    /** 导出空表：给出明确提示，不产生附件。 */
    @Test
    void exportEmptyTableReturnsClearHint() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        when(excelService.getActiveWorkbook(eq("user-1")))
            .thenReturn(new ExcelTable("user-1", "空表"));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "导出表格", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("还没有生成表格"));
        assertTrue(result.attachments().isEmpty());
        verify(excelService, never()).toXlsx(any());
    }

    /** 新建工作簿指令不需要活动表：直接新建并切换为当前表格（纯文字回复）。 */
    @Test
    void createWorkbookInstructionCreatesAndSwitchesActiveTable() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "新建表格 销售表", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("已新建表格「销售表」"));
        assertTrue(result.text().contains("切换为当前表格"));
        assertTrue(result.attachments().isEmpty());
        verify(excelService).createWorkbook("user-1", "销售表");
    }

    /** 工作簿管理指令不需要活动表：没有活动表也能查看表格列表。 */
    @Test
    void workbookListInstructionWorksWithoutActiveWorkbook() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "销售表");
        table.setId("t1");
        when(excelService.listWorkbooks("user-1")).thenReturn(List.of(table));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查看表格列表", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("共 1 张表格"));
        assertTrue(result.text().contains("销售表"));
        assertTrue(result.attachments().isEmpty());
        verify(excelService, never()).save(any());
    }

    /** 「新建表格 X」之后，旧指令作用于新表（活动表）。 */
    @Test
    void oldInstructionsActOnNewWorkbookAfterCreate() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable[] active = new ExcelTable[1];
        when(excelService.createWorkbook(eq("user-1"), anyString())).thenAnswer(inv -> {
            ExcelTable table = new ExcelTable("user-1", inv.getArgument(1));
            active[0] = table;
            return table;
        });
        when(excelService.getActiveWorkbook(eq("user-1"))).thenAnswer(inv -> active[0]);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        assertTrue(skill.execute(definition,
            new SkillRequest("user-1", "新建表格 销售表", "", "", "")).success());

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市\n张三,北京", "", "", ""));

        assertTrue(result.success());
        // 旧指令作用于刚新建的活动表（而不是新建一张新表）
        assertEquals(List.of("姓名", "城市"), active[0].getHeaders());
        assertEquals(List.of(List.of("张三", "北京")), active[0].getRows());
        verify(excelService, atLeastOnce()).save(active[0]);
    }

    // ============================
    // 审计日志：成功/失败都记录、操作类型拼接、null 审计不报错
    // ============================
    @Test
    void successfulOperationRecordsAuditLog() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, null, auditService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市", "", "", ""));

        assertTrue(result.success());
        // 成功也记录：operation 为操作类型名，success=true，detail 为结果文案
        verify(auditService).record(eq("user-1"), isNull(), eq("CREATE_TABLE"),
            eq(true), anyString());
    }

    @Test
    void failedOperationRecordsAuditLog() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setRows(fullRows(ExcelService.MAX_TABLE_ROWS));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, null, auditService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "添加一行：王五,35", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("表格超出上限"));
        // 失败也记录：success=false
        verify(auditService).record(eq("user-1"), isNull(), eq("ADD_ROW"),
            eq(false), anyString());
    }

    @Test
    void compositeOperationRecordsJoinedAuditOperation() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setHeaders(List.of("地区", "销售额"));
        table.setRows(new ArrayList<>(List.of(
            List.of("北京", "100"), List.of("北京", "100"), List.of("上海", "200"))));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, null, auditService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "删除重复订单，然后按地区汇总销售额", "", "", ""));

        assertTrue(result.success());
        // 复合计划：操作类型用 + 拼接
        verify(auditService).record(eq("user-1"), isNull(),
            eq("DEDUPLICATE+GROUP_SUMMARY"), eq(true), anyString());
    }

    @Test
    void exceptionPathStillRecordsAuditFailure() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenThrow(
            new IllegalArgumentException("❌ 公式存在错误，已取消导出。"));
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, null, auditService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,数值\n张三,=1/0", "", "", ""));

        assertFalse(result.success());
        // 异常路径（如公式错误）也必须写入审计，success=false
        verify(auditService).record(eq("user-1"), isNull(),
            eq("CREATE_TABLE"), eq(false), contains("公式存在错误"));
    }

    @Test
    void nullAuditServiceDoesNotBreakExecution() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市", "", "", ""));

        // 单参数构造器（audit 为 null）：执行正常，不写审计
        assertTrue(result.success());
    }

    // ============================
    // 操作日志指令（AUDIT_LIST）
    // ============================
    @Test
    void auditListInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        ExcelAuditLog log = new ExcelAuditLog("user-1", "t1", "SORT", true, "✅ 已按年龄排序。");
        when(auditService.list("user-1", 10)).thenReturn(List.of(log));
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, null, auditService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查看操作日志", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("最近 1 条操作记录"));
        assertTrue(result.text().contains("排序"));
        assertTrue(result.text().contains("成功"));
        // 操作日志不需要活动表
        verify(excelService, never()).getActiveWorkbook(anyString());
    }

    @Test
    void auditListWithNoLogsReturnsHint() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        when(auditService.list("user-1", 10)).thenReturn(List.of());
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService, null, auditService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "操作历史", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("还没有操作记录"));
    }

    @Test
    void auditListWithoutAuditServiceFailsGracefully() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查看操作日志", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("操作日志服务不可用"));
    }

    // ============================
    // 行列数上限
    // ============================
    @Test
    void createTableOverRowLimitRejectedWithoutPersist() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        StringBuilder instruction = new StringBuilder("生成表格：姓名,年龄\n");
        for (int i = 0; i <= ExcelService.MAX_TABLE_ROWS; i++) {
            instruction.append("张").append(i).append(",25\n");
        }
        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", instruction.toString(), "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("表格超出上限"));
        // 超限不落库：不保存、不产生版本快照
        verify(excelService, never()).save(any());
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }

    @Test
    void createTableOverColumnLimitRejectedWithoutPersist() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = new ExcelTable("user-1", "空表");
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        StringBuilder headers = new StringBuilder();
        for (int i = 0; i <= ExcelService.MAX_TABLE_COLUMNS; i++) {
            if (i > 0) headers.append(",");
            headers.append("列").append(i);
        }
        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：" + headers + "\n张,1", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("表格超出上限"));
        verify(excelService, never()).save(any());
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }

    @Test
    void addRowAtRowLimitRejectedWithoutPersist() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        table.setRows(fullRows(ExcelService.MAX_TABLE_ROWS));
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "添加一行：王五,35", "", "", ""));

        assertFalse(result.success());
        assertTrue(result.text().contains("表格超出上限"));
        // 超限不落库、不产生版本快照
        verify(excelService, never()).save(any());
        verify(excelService, never()).snapshotVersion(any(), anyString());
    }

    // ============================
    // 版本对比（VERSION_DIFF）
    // ============================
    @Test
    void versionDiffInstructionEndToEnd() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.diffVersions(table)).thenReturn(
            "📊 与上一版本对比：表头无变化；新增 1 行 / 删除 0 行 / 修改 0 行。");
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "版本对比", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("与上一版本对比"));
        assertTrue(result.text().contains("新增 1 行"));
        verify(excelService).diffVersions(table);
    }

    @Test
    void versionDiffWithoutVersionsReturnsHint() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.getActiveWorkbook(eq("user-1"))).thenReturn(table);
        when(excelService.diffVersions(table)).thenReturn("还没有可对比的版本");
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "对比上一版", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("还没有可对比的版本"));
    }

    /** 生成指定行数的行数据（姓名,年龄）。 */
    private static List<List<String>> fullRows(int count) {
        List<List<String>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(List.of("姓名" + i, "25"));
        }
        return rows;
    }
}
