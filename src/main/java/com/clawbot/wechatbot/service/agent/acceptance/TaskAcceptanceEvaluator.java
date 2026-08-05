package com.clawbot.wechatbot.service.agent.acceptance;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;

import java.util.Map;

/** 验证任务执行结果是否满足规划阶段定义的输出契约。 */
public interface TaskAcceptanceEvaluator {
    TaskEvaluation evaluate(
        AgentTask task,
        AgentTaskResult result,
        Map<String, AgentTaskResult> verifiedDependencies
    );
}
