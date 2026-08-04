package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(excelService.loadOrCreate(eq("user-1"), anyString()))
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
        when(excelService.loadOrCreate(eq("user-1"), anyString()))
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString()))
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
        when(excelService.toXlsx(any())).thenReturn(new byte[]{1, 2, 3});
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "生成表格：姓名,城市", "", "", ""));

        assertTrue(result.success());
        assertEquals(List.of("姓名", "城市"), table.getHeaders());
        verify(excelService).save(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
    }

    @Test
    void undoPhraseWithDeleteDescriptionRoutesToRollbackNotDelete() throws Exception {
        ExcelService excelService = mock(ExcelService.class);
        ExcelTable table = existingTable();
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
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
        when(excelService.loadOrCreate(eq("user-1"), anyString())).thenReturn(table);
        when(excelService.versionCount(table)).thenReturn(0L);
        ExcelOperationSkill skill = new ExcelOperationSkill(excelService);

        SkillResult result = skill.execute(definition,
            new SkillRequest("user-1", "查看版本", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("还没有版本记录"));
    }
}
