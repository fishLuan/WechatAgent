package com.clawbot.wechatbot.skills.validation;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** 专属 Skill 校验器使用的只读上下文。 */
public record SkillValidationContext(
    AgentTask task,
    AgentTaskResult result,
    JsonNode normalizedOutput,
    Map<String, AgentTaskResult> verifiedDependencies
) {
    public SkillValidationContext {
        normalizedOutput = normalizedOutput == null
            ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : normalizedOutput.deepCopy();
        verifiedDependencies = verifiedDependencies == null
            ? Map.of() : Map.copyOf(verifiedDependencies);
    }

    @Override
    public JsonNode normalizedOutput() {
        return normalizedOutput.deepCopy();
    }
}
