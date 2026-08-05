package com.clawbot.wechatbot.service.agent.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

/** 已替换全部 $ref 的任务输入及其数据血缘。 */
public record ResolvedTaskInput(JsonNode input, List<DataLineageRecord> lineage) {
    public ResolvedTaskInput {
        input = input == null
            ? JsonNodeFactory.instance.objectNode() : input.deepCopy();
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
    }

    @Override
    public JsonNode input() {
        return input.deepCopy();
    }
}
