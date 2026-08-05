package com.clawbot.wechatbot.feature.bilibili.validation;

import com.clawbot.wechatbot.service.agent.acceptance.TaskDecision;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.skills.validation.SkillResultValidator;
import com.clawbot.wechatbot.skills.validation.SkillValidationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public final class BilibiliSkillResultValidator implements SkillResultValidator {
    private static final List<String> FAILURE_MARKERS = List.of(
        "❌", "订阅失败", "搜索作品失败", "没有在b站找到", "没有找到作品",
        "暂时受限", "暂不支持该b站子任务", "工具调用次数超过限制");

    @Override
    public String validatorName() { return "bilibili"; }

    @Override
    public TaskEvaluation validate(SkillValidationContext context) {
        String instruction = context.task().instruction().toLowerCase(Locale.ROOT);
        String normalized = context.result().text().toLowerCase(Locale.ROOT);
        for (String marker : FAILURE_MARKERS) {
            if (normalized.contains(marker.toLowerCase(Locale.ROOT))) {
                return reject("BILIBILI_RESULT_INVALID", "B站任务返回失败或受限结果：" + marker);
            }
        }
        if (instruction.contains("订阅")
            && !normalized.contains("订阅成功")
            && !normalized.contains("已订阅")) {
            return reject("BILIBILI_SUBSCRIPTION_NOT_CONFIRMED", "结果未明确确认订阅成功");
        }
        return TaskEvaluation.pass(context.normalizedOutput());
    }

    private TaskEvaluation reject(String code, String reason) {
        return new TaskEvaluation(TaskDecision.REPLAN, code, reason, null,
            List.of(reason), "重新搜索并核对作品标识后再执行B站任务");
    }
}
