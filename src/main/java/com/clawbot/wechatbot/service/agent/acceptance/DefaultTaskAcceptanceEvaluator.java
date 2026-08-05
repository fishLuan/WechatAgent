package com.clawbot.wechatbot.service.agent.acceptance;

import com.clawbot.wechatbot.service.agent.AcceptanceCriterion;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 基于 expectedOutput 和 acceptanceCriteria 的确定性验收器。 */
public final class DefaultTaskAcceptanceEvaluator
    implements TaskAcceptanceEvaluator {
    private final ObjectMapper mapper;

    public DefaultTaskAcceptanceEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TaskEvaluation evaluate(
        AgentTask task,
        AgentTaskResult result,
        Map<String, AgentTaskResult> verifiedDependencies
    ) {
        if (result == null || !result.succeeded()) {
            String reason = result == null ? "任务没有返回结果" : result.error();
            return failure(
                TaskDecision.RETRY,
                "TASK_EXECUTION_FAILED",
                reason,
                List.of(),
                "检查任务参数或外部服务状态后重试当前任务");
        }

        JsonNode output = normalizeOutput(result);
        List<String> failures = new ArrayList<>();
        validateExpectedOutput(task.expectedOutput(), output, "$", failures);
        for (AcceptanceCriterion criterion : task.acceptanceCriteria()) {
            if (!criterion.required()) continue;
            String failure = evaluateCriterion(criterion, output);
            if (failure != null) failures.add(failure);
        }

        if (!failures.isEmpty()) {
            return failure(
                TaskDecision.REPLAN,
                "TASK_ACCEPTANCE_FAILED",
                "任务结果未满足 " + failures.size() + " 项验收要求",
                failures,
                "调整当前任务参数、补充前置任务或局部重规划");
        }
        return TaskEvaluation.pass(output);
    }

    private JsonNode normalizeOutput(AgentTaskResult result) {
        String text = result.text() == null ? "" : result.text().trim();
        JsonNode parsed = parseJson(text);
        if (parsed != null) return parsed;

        ObjectNode output = mapper.createObjectNode();
        output.put("text", text);
        ArrayNode attachments = output.putArray("attachments");
        for (AgentAttachment attachment : result.attachments()) {
            ObjectNode item = attachments.addObject();
            item.put("type", attachment.type().name());
            item.put("fileName", attachment.fileName());
            item.put("caption", attachment.caption());
            item.put("size", attachment.content().length);
        }
        return output;
    }

    private JsonNode parseJson(String text) {
        if (text.isBlank()) return null;
        try {
            return mapper.readTree(text);
        } catch (Exception ignored) {
            int start = Math.min(
                positiveOrMax(text.indexOf('{')),
                positiveOrMax(text.indexOf('[')));
            int objectEnd = text.lastIndexOf('}');
            int arrayEnd = text.lastIndexOf(']');
            int end = Math.max(objectEnd, arrayEnd);
            if (start == Integer.MAX_VALUE || end <= start) return null;
            try {
                return mapper.readTree(text.substring(start, end + 1));
            } catch (Exception invalidEmbeddedJson) {
                return null;
            }
        }
    }

    private int positiveOrMax(int index) {
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private void validateExpectedOutput(
        JsonNode schema, JsonNode output, String path, List<String> failures
    ) {
        if (schema == null || !schema.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldPath = path + "." + field.getKey();
            JsonNode actual = resolve(output, fieldPath);
            if (isMissing(actual)) {
                failures.add(fieldPath + " 不存在");
                continue;
            }
            JsonNode expected = field.getValue();
            if (expected.isObject()) {
                validateExpectedOutput(expected, output, fieldPath, failures);
            } else if (expected.isTextual()
                && isKnownType(expected.asText())
                && !matchesType(actual, expected.asText())) {
                failures.add(fieldPath + " 类型应为 " + expected.asText());
            }
        }
    }

    private String evaluateCriterion(
        AcceptanceCriterion criterion, JsonNode output
    ) {
        JsonNode actual = resolve(output, criterion.path());
        JsonNode expected = criterion.expectedValue();
        boolean passed;
        try {
            passed = switch (criterion.operator()) {
                case EXISTS -> !isMissing(actual);
                case NOT_EMPTY -> !isEmpty(actual);
                case EQUALS -> jsonEquals(actual, expected);
                case NOT_EQUALS -> !jsonEquals(actual, expected);
                case CONTAINS -> contains(actual, expected);
                case GREATER_THAN -> compare(actual, expected) > 0;
                case GREATER_THAN_OR_EQUALS -> compare(actual, expected) >= 0;
                case LESS_THAN -> compare(actual, expected) < 0;
                case LESS_THAN_OR_EQUALS -> compare(actual, expected) <= 0;
                case MATCHES_REGEX -> matchesRegex(actual, expected);
                case TYPE_IS -> matchesType(actual, expected.asText());
            };
        } catch (RuntimeException ignored) {
            passed = false;
        }
        if (passed) return null;
        String label = criterion.description().isBlank()
            ? criterion.path() + " 未满足 " + criterion.operator()
            : criterion.description();
        return label;
    }

    private JsonNode resolve(JsonNode root, String path) {
        if (root == null || path == null || !path.startsWith("$")) {
            return MissingNode.getInstance();
        }
        JsonNode current = root;
        String remaining = path.substring(1);
        int index = 0;
        while (index < remaining.length()) {
            char marker = remaining.charAt(index);
            if (marker == '.') {
                int next = index + 1;
                while (next < remaining.length()
                    && remaining.charAt(next) != '.'
                    && remaining.charAt(next) != '[') next++;
                String field = remaining.substring(index + 1, next);
                if (field.isBlank() || !current.isObject()) {
                    return MissingNode.getInstance();
                }
                current = current.path(field);
                index = next;
            } else if (marker == '[') {
                int end = remaining.indexOf(']', index);
                if (end < 0 || !current.isArray()) return MissingNode.getInstance();
                try {
                    int arrayIndex = Integer.parseInt(
                        remaining.substring(index + 1, end));
                    if (arrayIndex < 0 || arrayIndex >= current.size()) {
                        return MissingNode.getInstance();
                    }
                    current = current.get(arrayIndex);
                } catch (NumberFormatException ignored) {
                    return MissingNode.getInstance();
                }
                index = end + 1;
            } else {
                return MissingNode.getInstance();
            }
        }
        return current == null ? MissingNode.getInstance() : current;
    }

    private boolean isMissing(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull();
    }

    private boolean isEmpty(JsonNode value) {
        if (isMissing(value)) return true;
        if (value.isTextual()) return value.asText().isBlank();
        if (value.isArray() || value.isObject()) return value.isEmpty();
        return false;
    }

    private boolean jsonEquals(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null || expected.isNull()) return false;
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        return actual.equals(expected)
            || actual.asText().trim().equalsIgnoreCase(expected.asText().trim());
    }

    private boolean contains(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null || expected.isNull()) return false;
        if (actual.isArray()) {
            for (JsonNode item : actual) if (jsonEquals(item, expected)) return true;
            return false;
        }
        return actual.asText().contains(expected.asText());
    }

    private int compare(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null || expected.isNull()) {
            throw new IllegalArgumentException("比较值不存在");
        }
        BigDecimal left = new BigDecimal(actual.asText());
        BigDecimal right = new BigDecimal(expected.asText());
        return left.compareTo(right);
    }

    private boolean matchesRegex(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null || !expected.isTextual()) return false;
        try {
            return Pattern.compile(expected.asText()).matcher(actual.asText()).matches();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private boolean isKnownType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string", "number", "integer", "boolean", "object", "array", "null" -> true;
            default -> false;
        };
    }

    private boolean matchesType(JsonNode actual, String rawType) {
        if (isMissing(actual) || rawType == null) return false;
        return switch (rawType.trim().toLowerCase(Locale.ROOT)) {
            case "string" -> actual.isTextual();
            case "number" -> actual.isNumber();
            case "integer" -> actual.isIntegralNumber();
            case "boolean" -> actual.isBoolean();
            case "object" -> actual.isObject();
            case "array" -> actual.isArray();
            case "null" -> actual.isNull();
            default -> false;
        };
    }

    private TaskEvaluation failure(
        TaskDecision decision,
        String code,
        String reason,
        List<String> failedCriteria,
        String correctiveHint
    ) {
        return new TaskEvaluation(
            decision, code, reason, mapper.createObjectNode(),
            failedCriteria, correctiveHint);
    }
}
