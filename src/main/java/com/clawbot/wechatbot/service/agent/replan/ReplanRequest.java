package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;

import java.util.List;
import java.util.Map;

/** 局部重规划器所需的最小上下文。 */
public record ReplanRequest(
    String originalUserRequest,
    AgentTask failedTask,
    AgentTaskResult failedResult,
    TaskEvaluation evaluation,
    Map<String, AgentTaskResult> verifiedResults,
    List<AgentTask> remainingTasks,
    int remainingTaskBudget
) {
    public ReplanRequest {
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        if (failedTask == null) throw new IllegalArgumentException("失败任务不能为空");
        if (evaluation == null) throw new IllegalArgumentException("验收结论不能为空");
        verifiedResults = verifiedResults == null ? Map.of() : Map.copyOf(verifiedResults);
        remainingTasks = remainingTasks == null ? List.of() : List.copyOf(remainingTasks);
        remainingTaskBudget = Math.max(0, remainingTaskBudget);
    }
}
