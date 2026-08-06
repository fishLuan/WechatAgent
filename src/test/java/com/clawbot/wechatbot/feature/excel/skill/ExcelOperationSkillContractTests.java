package com.clawbot.wechatbot.feature.excel.skill;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableRepository;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelOperationSkillContractTests {

    @Test
    void createsNewsWorkbookFromStructuredDependency() throws Exception {
        ExcelTableRepository repository = mock(ExcelTableRepository.class);
        when(repository.findByWechatUserId("user-1")).thenReturn(Optional.empty());
        when(repository.save(any(ExcelTable.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        ExcelOperationSkill skill = new ExcelOperationSkill(new ExcelService(repository));
        ObjectMapper mapper = new ObjectMapper();
        SkillRequest request = new SkillRequest(
            "user-1", "把新闻生成一份 Excel 表格", "", "", "",
            mapper.readTree("""
                {"items":[{"title":"北京新闻","description":"摘要",
                "source":"测试来源","publish_time":"2026-08-06 10:00:00",
                "url":"https://example.com/news"}]}
                """), List.of());

        SkillResult result = skill.execute(null, request);

        assertTrue(result.success());
        assertEquals(1, result.attachments().size());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(
            result.attachments().get(0).content()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("标题", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("北京新闻", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("测试来源", sheet.getRow(1).getCell(2).getStringCellValue());
        }
    }
}
