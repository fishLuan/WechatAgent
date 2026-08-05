package com.clawbot.wechatbot.service.agent.replan;

import java.util.List;

/** 重规划模型返回的受控修改集合。 */
public record ReplanResult(List<PlanMutation> mutations, String reason) {
    public ReplanResult {
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
        reason = reason == null ? "" : reason.trim();
    }
}
