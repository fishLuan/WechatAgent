package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 生成表格的防静默覆盖保护测试。 */
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
}
