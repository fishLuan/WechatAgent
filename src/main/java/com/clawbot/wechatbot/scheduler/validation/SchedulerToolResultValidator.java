package com.clawbot.wechatbot.scheduler.validation;

import com.clawbot.wechatbot.scheduler.tool.SchedulerTool;
import com.clawbot.wechatbot.service.agent.validation.ToolResultValidator;
import com.clawbot.wechatbot.service.agent.validation.ToolValidationAction;
import com.clawbot.wechatbot.service.agent.validation.ToolValidationContext;
import com.clawbot.wechatbot.service.agent.validation.ToolValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public final class SchedulerToolResultValidator implements ToolResultValidator {
    private final ObjectMapper mapper;

    public SchedulerToolResultValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(String toolName) {
        return SchedulerTool.TOOL_NAME.equals(toolName);
    }

    @Override
    public int order() { return 20; }

    @Override
    public ToolValidationResult validate(ToolValidationContext context) {
        if (!context.outcome().success()) return ToolValidationResult.pass(1D);
        try {
            JsonNode args = context.arguments();
            JsonNode output = mapper.readTree(context.outcome().content());
            if (output.path("confirmation_required").asBoolean(false)
                || "WAITING_CONFIRMATION".equalsIgnoreCase(
                    output.path("execution_status").asText(""))) {
                return ToolValidationResult.pass(1D);
            }
            if (!output.path("success").asBoolean(false)) {
                return invalid("调度工具结果未确认执行成功");
            }
            return switch (args.path("action").asText("")) {
                case "create_subscription" -> validateCreate(args, output);
                case "cancel_subscription" -> validateCancel(args, output);
                case "list_subscriptions" -> output.path("subscriptions").isArray()
                    ? ToolValidationResult.pass(1D) : invalid("订阅列表结果缺少 subscriptions 数组");
                default -> invalid("调度工具返回了未知 action");
            };
        } catch (Exception exception) {
            return invalid("调度工具结果不是合法JSON：" + exception.getMessage());
        }
    }

    private ToolValidationResult validateCreate(JsonNode args, JsonNode output) {
        if (output.path("subscription_id").asText("").isBlank()) {
            return invalid("创建结果缺少 subscription_id");
        }
        String nextFire = output.path("next_fire_time").asText("");
        if (nextFire.isBlank()) return invalid("创建结果缺少 next_fire_time");
        boolean requestedOneTime = args.path("is_one_time").asBoolean(false);
        if (output.path("is_one_time").asBoolean(!requestedOneTime) != requestedOneTime) {
            return invalid("创建结果的单次/周期类型与请求不一致");
        }
        String dailyTime = args.path("time_daily_hhmm").asText("");
        if (!requestedOneTime && !dailyTime.isBlank() && !nextFire.contains(dailyTime)) {
            return invalid("下次执行时间与用户指定的每日时间不一致");
        }
        return ToolValidationResult.pass(1D);
    }

    private ToolValidationResult validateCancel(JsonNode args, JsonNode output) {
        if (output.path("message").asText("").isBlank()) {
            return invalid("取消结果缺少确认消息");
        }
        if ((args.path("cancel_all").asBoolean(false)
            || args.path("cancel_matching_all").asBoolean(false))
            && (!output.has("cancel_count") || !output.path("cancel_count").canConvertToInt())) {
            return invalid("批量取消结果缺少 cancel_count");
        }
        return ToolValidationResult.pass(1D);
    }

    private ToolValidationResult invalid(String reason) {
        return new ToolValidationResult(ToolValidationAction.REPLAN, 1D,
            "SCHEDULER_RESULT_INVALID", reason, "重新规划调度参数并再次调用 scheduler_manage");
    }
}
