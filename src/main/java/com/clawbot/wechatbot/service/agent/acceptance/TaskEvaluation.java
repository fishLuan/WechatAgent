package com.clawbot.wechatbot.service.agent.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

/** 单个外层任务的结构化验收结论。 */
public record TaskEvaluation(
    TaskDecision decision,
    String code,
    String reason,
    JsonNode verifiedOutput,
    List<String> failedCriteria,
    String correctiveHint
) {
    public TaskEvaluation {
        decision = decision == null ? TaskDecision.ABORT : decision;
        code = code == null ? "" : code;
        reason = reason == null ? "" : reason;
        verifiedOutput = verifiedOutput == null
            ? JsonNodeFactory.instance.objectNode() : verifiedOutput.deepCopy();
        failedCriteria = failedCriteria == null
            ? List.of() : List.copyOf(failedCriteria);
        correctiveHint = correctiveHint == null ? "" : correctiveHint;
    }

    public static TaskEvaluation pass(JsonNode output) {
        return new TaskEvaluation(
            TaskDecision.PASS, "", "", output, List.of(), "");
    }

    public boolean passed() {
        return decision == TaskDecision.PASS;
    }

    @Override
    public JsonNode verifiedOutput() {
        return verifiedOutput.deepCopy();
    }
}
