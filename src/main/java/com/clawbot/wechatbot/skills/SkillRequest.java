package com.clawbot.wechatbot.skills;

import com.clawbot.wechatbot.service.agent.reference.DataLineageRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

/** Skill执行所需的用户身份、任务指令和只读上下文。 */
public record SkillRequest(
    String userId,
    String instruction,
    String history,
    String supportingContext,
    String dependencyText,
    JsonNode resolvedInput,
    List<DataLineageRecord> lineage
) {
    public SkillRequest {
        userId = safe(userId);
        instruction = safe(instruction);
        history = safe(history);
        supportingContext = safe(supportingContext);
        dependencyText = safe(dependencyText);
        resolvedInput = resolvedInput == null
            ? JsonNodeFactory.instance.objectNode() : resolvedInput.deepCopy();
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
    }

    public SkillRequest(
        String userId,
        String instruction,
        String history,
        String supportingContext,
        String dependencyText
    ) {
        this(userId, instruction, history, supportingContext, dependencyText,
            JsonNodeFactory.instance.objectNode(), List.of());
    }

    @Override
    public JsonNode resolvedInput() {
        return resolvedInput.deepCopy();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
