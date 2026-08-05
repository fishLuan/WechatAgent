package com.clawbot.wechatbot.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class RiskPolicy {
    public RiskDecision evaluate(String toolName, JsonNode args) {
        String action = args == null ? "" : args.path("action").asText("");
        if ("scheduler_manage".equals(toolName)
            && "cancel_subscription".equals(action)
            && args.path("cancel_all").asBoolean(false)) {
            return new RiskDecision(true, "HIGH",
                "取消你的全部定时订阅（包括动漫、电影、电视剧及其他定时推送）");
        }
        if ("scheduler_manage".equals(toolName)
            && "cancel_subscription".equals(action)
            && args.path("cancel_matching_all").asBoolean(false)) {
            String type = args.path("task_type").asText("指定类型");
            String target = "BILIBILI_PUSH".equals(type)
                ? "全部B站定时推送（包括动漫、电影和电视剧）"
                : "全部 " + type + " 类型的定时任务";
            return new RiskDecision(true, "HIGH", "关闭你的" + target);
        }
        if ("bilibili_manage".equals(toolName)
            && ("disable_push".equals(action) || "restore_push_days".equals(action))) {
            return new RiskDecision(true, "MEDIUM", "修改B站全局推送设置：" + action);
        }
        return RiskDecision.safe();
    }
}
