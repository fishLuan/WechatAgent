package com.clawbot.wechatbot.service.agent.validation;

import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 在工具结果进入 Agent messages 前执行验收，并把不可信结果替换为纠偏指令。 */
public final class ToolValidationPipeline {
    private final ObjectMapper mapper;
    private final List<ToolResultValidator> validators;
    private final double minConfidence;

    public ToolValidationPipeline(
        ObjectMapper mapper,
        List<ToolResultValidator> validators,
        double minConfidence
    ) {
        this.mapper = mapper;
        this.minConfidence = minConfidence;
        this.validators = new ArrayList<>(validators == null ? List.of() : validators);
        this.validators.add(new GenericToolResultValidator(mapper));
        this.validators.sort(Comparator.comparingInt(ToolResultValidator::order));
    }

    public ValidatedToolOutcome validate(
        String originalUserRequest,
        String toolName,
        String rawArguments,
        ToolExecutionOutcome outcome,
        Map<String, String> verifiedResults
    ) {
        JsonNode arguments = parseArguments(rawArguments);
        ToolValidationContext context = new ToolValidationContext(
            originalUserRequest, toolName, arguments, outcome, verifiedResults);

        for (ToolResultValidator validator : validators) {
            if (!validator.supports(toolName)) continue;
            ToolValidationResult result = validator.validate(context);
            if (!result.passed()) return rejected(outcome, result);
            if (result.confidence() < minConfidence) {
                return rejected(outcome, new ToolValidationResult(
                    ToolValidationAction.REPLAN,
                    result.confidence(),
                    "LOW_RESULT_CONFIDENCE",
                    "工具结果可信度不足，不能作为后续步骤输入",
                    "补充约束、换用数据来源或重新规划当前步骤"));
            }
        }
        return new ValidatedToolOutcome(outcome, ToolValidationResult.pass(1D));
    }

    private ValidatedToolOutcome rejected(
        ToolExecutionOutcome original, ToolValidationResult validation
    ) {
        ObjectNode error = mapper.createObjectNode();
        error.put("success", false);
        error.put("verified", false);
        error.put("code", validation.code());
        error.put("action", validation.action().name());
        error.put("retryable", validation.action() == ToolValidationAction.RETRY
            || validation.action() == ToolValidationAction.REPLAN);
        error.put("error", validation.reason());
        error.put("corrective_action", validation.correctiveAction());
        error.put("discarded_untrusted_result", true);
        try {
            String content = mapper.writeValueAsString(error);
            boolean retryable = error.path("retryable").asBoolean(false);
            return new ValidatedToolOutcome(
                new ToolExecutionOutcome(content, false, retryable, validation.code()),
                validation);
        } catch (Exception ignored) {
            return new ValidatedToolOutcome(original, validation);
        }
    }

    private JsonNode parseArguments(String rawArguments) {
        try {
            return mapper.readTree(
                rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    public record ValidatedToolOutcome(
        ToolExecutionOutcome outcome,
        ToolValidationResult validation
    ) {
    }
}
