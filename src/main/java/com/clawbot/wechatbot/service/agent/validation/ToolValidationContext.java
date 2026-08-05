package com.clawbot.wechatbot.service.agent.validation;

import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** 单次工具结果校验所需的只读上下文。 */
public record ToolValidationContext(
    String originalUserRequest,
    String toolName,
    JsonNode arguments,
    ToolExecutionOutcome outcome,
    Map<String, String> verifiedResults
) {
    public ToolValidationContext {
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        toolName = toolName == null ? "" : toolName;
        verifiedResults = verifiedResults == null ? Map.of() : Map.copyOf(verifiedResults);
    }
}
