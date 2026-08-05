package com.clawbot.wechatbot.service.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 对所有工具执行结构、有效载荷和关键参数回显一致性校验。 */
public final class GenericToolResultValidator implements ToolResultValidator {
    private static final Map<String, Set<String>> IDENTITY_FIELDS = identityFields();
    private static final Set<String> METADATA_FIELDS = Set.of(
        "success", "code", "retryable", "message", "notice", "status");

    private final ObjectMapper mapper;

    public GenericToolResultValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(String toolName) {
        return true;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public ToolValidationResult validate(ToolValidationContext context) {
        if (!context.outcome().success()) {
            return new ToolValidationResult(
                context.outcome().retryable()
                    ? ToolValidationAction.RETRY : ToolValidationAction.ABORT,
                1D,
                context.outcome().code().isBlank()
                    ? "TOOL_REPORTED_FAILURE" : context.outcome().code(),
                "工具没有返回成功结果",
                context.outcome().retryable() ? "修正参数后重试当前步骤" : "停止当前步骤");
        }
        if (context.outcome().content().isBlank()) {
            return invalid("EMPTY_TOOL_RESULT", "工具返回内容为空", ToolValidationAction.RETRY);
        }

        JsonNode result;
        try {
            result = mapper.readTree(context.outcome().content());
        } catch (Exception ignored) {
            // 老工具可能返回普通文本。它无法做字段级校验，但仍可交给模型使用。
            return ToolValidationResult.pass(0.65D);
        }
        if (result == null || result.isNull() || result.isMissingNode()) {
            return invalid("EMPTY_TOOL_RESULT", "工具返回了空 JSON", ToolValidationAction.RETRY);
        }
        if (result.isObject() && !hasBusinessPayload(result)) {
            return invalid(
                "MISSING_BUSINESS_PAYLOAD",
                "工具虽然报告成功，但没有返回可供后续步骤使用的业务数据",
                ToolValidationAction.RETRY);
        }

        ToolValidationResult consistency = validateIdentityFields(
            context.arguments(), result);
        return consistency == null ? ToolValidationResult.pass(0.9D) : consistency;
    }

    private ToolValidationResult validateIdentityFields(
        JsonNode arguments, JsonNode result
    ) {
        if (arguments == null || !arguments.isObject()) return null;
        for (Map.Entry<String, Set<String>> entry : IDENTITY_FIELDS.entrySet()) {
            JsonNode expected = arguments.get(entry.getKey());
            if (!isComparable(expected)) continue;
            JsonNode actual = findFirst(result, entry.getValue());
            if (!isComparable(actual)) continue;
            if (!equivalent(entry.getKey(), expected.asText(), actual.asText())) {
                return new ToolValidationResult(
                    ToolValidationAction.REPLAN,
                    1D,
                    "TOOL_RESULT_ARGUMENT_MISMATCH",
                    "工具返回的 " + entry.getKey()
                        + " 与调用参数不一致，结果已丢弃",
                    "丢弃本次结果，检查参数映射后重新规划当前步骤");
            }
        }
        return null;
    }

    private JsonNode findFirst(JsonNode node, Set<String> names) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String name : names) {
                JsonNode value = node.get(name);
                if (isComparable(value)) return value;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findFirst(fields.next().getValue(), names);
                if (isComparable(found)) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findFirst(item, names);
                if (isComparable(found)) return found;
            }
        }
        return null;
    }

    private boolean isComparable(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode()
            && !node.asText("").isBlank();
    }

    private boolean equivalent(String field, String expected, String actual) {
        String left = normalize(expected);
        String right = normalize(actual);
        if ("city".equals(field)) {
            left = stripCitySuffix(left);
            right = stripCitySuffix(right);
        }
        if (left.equals(right)) return true;
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replace(" ", "");
    }

    private String stripCitySuffix(String value) {
        return value.replaceFirst("(特别行政区|自治州|地区|盟|市|县|区)$", "");
    }

    private boolean hasBusinessPayload(JsonNode result) {
        var fields = result.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!METADATA_FIELDS.contains(field.getKey())
                && !field.getValue().isNull()
                && !(field.getValue().isTextual() && field.getValue().asText().isBlank())) {
                return true;
            }
        }
        return false;
    }

    private ToolValidationResult invalid(
        String code, String reason, ToolValidationAction action
    ) {
        return new ToolValidationResult(
            action, 1D, code, reason, "修正参数或更换数据来源后重试");
    }

    private static Map<String, Set<String>> identityFields() {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        fields.put("city", Set.of("query_city", "requested_city"));
        fields.put("date", Set.of("query_date", "requested_date"));
        fields.put("from_currency", Set.of("source_currency"));
        fields.put("to_currency", Set.of("target_currency"));
        fields.put("extensions", Set.of("type"));
        return Map.copyOf(fields);
    }
}
