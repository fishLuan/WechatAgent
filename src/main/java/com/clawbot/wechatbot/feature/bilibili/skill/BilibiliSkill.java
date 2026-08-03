package com.clawbot.wechatbot.feature.bilibili.skill;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

/** Existing Bilibili workflow exposed through the generic skill executor SPI. */
@Component
public final class BilibiliSkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "bilibili";
    private final BilibiliCommandHandler commands;

    public BilibiliSkill(BilibiliCommandHandler commands) {
        this.commands = commands;
    }

    @Override
    public String executorName() {
        return EXECUTOR_NAME;
    }

    @Override
    public SkillResult execute(SkillDefinition definition, SkillRequest request) {
        if (request == null || request.userId().isBlank()) {
            return SkillResult.failure("Bilibili skill requires WeChat user context");
        }
        if (request.instruction().isBlank()) {
            return SkillResult.failure("Bilibili skill requires an instruction");
        }
        // 定时/预约推送请求：引导走 scheduler_manage 创建控制台可见的定时任务，而不是本技能
        if (looksLikeScheduledPush(request.instruction())) {
            return SkillResult.failure(
                "这是定时推送需求，不要用本技能。请调用 scheduler_manage 工具（action=create，"
                    + "task_type=BILIBILI_PUSH，params_json 写 {\"content_type\":\"MOVIE或BANGUMI或SERIES\",\"count\":数量}，"
                    + "时间参数用 time_daily_hhmm（每天重复）或 is_one_time+one_time_datetime（单次））创建定时任务。");
        }
        String reply = commands.handle(request.userId(), request.instruction());
        if (reply == null || reply.isBlank()
            || reply.startsWith("[UNHANDLED-BILIBILI-UNKNOWN]")) {
            return SkillResult.failure(
                "Unable to recognize Bilibili operation: " + request.instruction());
        }
        if (reply.startsWith("❌")) return SkillResult.failure(reply);
        return SkillResult.success(reply);
    }

    /** 定时推送请求检测：时间词 + 推送/推荐/提醒 语义 */
    private boolean looksLikeScheduledPush(String instruction) {
        if (instruction == null || instruction.isBlank()) return false;
        String text = instruction.trim();
        boolean hasTime = text.matches(".*(每天|每日|明天|后天|定时|预约|几点|固定时间|\\d+\\s*点|\\d{1,2}[:：]\\d{2}).*");
        boolean hasPush = text.matches(".*(推送|推荐|提醒|发给我|发一下).*");
        return hasTime && hasPush;
    }
}
