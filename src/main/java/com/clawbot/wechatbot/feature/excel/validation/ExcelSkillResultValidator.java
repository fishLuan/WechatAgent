package com.clawbot.wechatbot.feature.excel.validation;

import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.service.agent.acceptance.TaskDecision;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.skills.validation.SkillResultValidator;
import com.clawbot.wechatbot.skills.validation.SkillValidationContext;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;

@Component
public final class ExcelSkillResultValidator implements SkillResultValidator {
    private static final List<String> WRITE_WORDS = List.of(
        "生成", "创建", "制作", "新建", "添加", "增加", "修改", "更新", "删除", "移除", "导出");

    @Override
    public String validatorName() { return "excel"; }

    @Override
    public TaskEvaluation validate(SkillValidationContext context) {
        String instruction = context.task().instruction().toLowerCase(Locale.ROOT);
        boolean writesWorkbook = WRITE_WORDS.stream().anyMatch(instruction::contains);
        List<AgentAttachment> xlsxFiles = context.result().attachments().stream()
            .filter(a -> a.type() == AgentAttachment.AttachmentType.FILE)
            .filter(a -> a.fileName().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            .toList();
        if (!writesWorkbook && xlsxFiles.isEmpty()) {
            return TaskEvaluation.pass(context.normalizedOutput());
        }
        if (xlsxFiles.isEmpty()) return reject("Excel写入任务没有返回 .xlsx 文件");
        for (AgentAttachment file : xlsxFiles) {
            try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(file.content()))) {
                if (workbook.getNumberOfSheets() < 1) return reject("Excel文件不包含工作表");
            } catch (Exception exception) {
                return reject("Excel附件无法被正常打开：" + exception.getMessage());
            }
        }
        return TaskEvaluation.pass(context.normalizedOutput());
    }

    private TaskEvaluation reject(String reason) {
        return new TaskEvaluation(TaskDecision.REPLAN, "EXCEL_FILE_INVALID", reason,
            null, List.of(reason), "重新执行Excel操作并返回可读取的 .xlsx 文件");
    }
}
