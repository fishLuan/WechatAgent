package com.clawbot.wechatbot.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/** 对单个任务输出字段的机器可检查验收条件。 */
public record AcceptanceCriterion(
    String description,
    String path,
    AcceptanceOperator operator,
    JsonNode expectedValue,
    boolean required
) {
    public AcceptanceCriterion {
        description = description == null ? "" : description.trim();
        if (description.length() > 500) {
            throw new IllegalArgumentException("验收条件说明不能超过 500 字");
        }
        path = path == null ? "" : path.trim();
        if (path.isBlank() || !path.startsWith("$")) {
            throw new IllegalArgumentException("验收字段路径必须以 $ 开头");
        }
        if (path.length() > 300) {
            throw new IllegalArgumentException("验收字段路径不能超过 300 字");
        }
        if (operator == null) {
            throw new IllegalArgumentException("验收操作符不能为空");
        }
        expectedValue = expectedValue == null
            ? NullNode.getInstance() : expectedValue.deepCopy();
    }

    @Override
    public JsonNode expectedValue() {
        return expectedValue.deepCopy();
    }
}
