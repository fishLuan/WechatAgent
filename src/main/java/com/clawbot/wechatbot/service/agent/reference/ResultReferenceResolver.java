package com.clawbot.wechatbot.service.agent.reference;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 递归解析任务 input 中的受限 $ref，并生成不含明文的数据血缘。 */
public final class ResultReferenceResolver {
    private final ObjectMapper mapper;
    private final ReferencePolicy policy;

    public ResultReferenceResolver(ObjectMapper mapper, ReferencePolicy policy) {
        this.mapper = mapper;
        this.policy = policy;
    }

    public ResolvedTaskInput resolve(AgentTask task, AgentExecutionState state) {
        List<DataLineageRecord> lineage = new ArrayList<>();
        int[] referenceCount = {0};
        JsonNode resolved = resolveNode(
            task.input(), state, task, "$", 0, referenceCount, lineage);
        if (!resolved.isObject()) {
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.set("value", resolved);
            resolved = wrapped;
        }
        try {
            if (mapper.writeValueAsString(resolved).length()
                > policy.maxResolvedInputChars()) {
                throw new ReferenceResolutionException(
                    "REF_RESOLVED_INPUT_TOO_LARGE",
                    "解析后的任务输入超过 " + policy.maxResolvedInputChars() + " 字");
            }
        } catch (ReferenceResolutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ReferenceResolutionException(
                "REF_SERIALIZATION_FAILED", "无法检查解析后的任务输入");
        }
        return new ResolvedTaskInput(resolved, lineage);
    }

    private JsonNode resolveNode(
        JsonNode node,
        AgentExecutionState state,
        AgentTask targetTask,
        String targetPath,
        int depth,
        int[] referenceCount,
        List<DataLineageRecord> lineage
    ) {
        if (depth > policy.maxDepth()) {
            throw new ReferenceResolutionException(
                "REF_MAX_DEPTH_EXCEEDED", "$ref 输入嵌套超过限制");
        }
        if (isReferenceNode(node)) {
            if (++referenceCount[0] > policy.maxReferencesPerTask()) {
                throw new ReferenceResolutionException(
                    "REF_COUNT_EXCEEDED",
                    "单个任务的 $ref 数量超过 " + policy.maxReferencesPerTask());
            }
            ResultReference reference = ResultReference.parse(
                node.path("$ref").asText(), policy.maxPathLength());
            if (!targetTask.dependencies().contains(reference.taskId())) {
                throw new ReferenceResolutionException(
                    "REF_DEPENDENCY_NOT_DECLARED",
                    "任务 " + targetTask.id() + " 未声明依赖 " + reference.taskId());
            }
            JsonNode value = state.readVerifiedValue(
                reference.taskId(), reference.path());
            lineage.add(lineage(reference, targetTask.id(), targetPath, value));
            return value.deepCopy();
        }
        if (node != null && node.isObject()) {
            if (node.has("$ref")) {
                throw new ReferenceResolutionException(
                    "REF_AMBIGUOUS_NODE", "$ref 节点不能同时包含其他字段");
            }
            ObjectNode result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), resolveNode(
                    field.getValue(), state, targetTask,
                    targetPath + "." + field.getKey(), depth + 1,
                    referenceCount, lineage));
            }
            return result;
        }
        if (node != null && node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                result.add(resolveNode(
                    node.get(index), state, targetTask,
                    targetPath + "[" + index + "]", depth + 1,
                    referenceCount, lineage));
            }
            return result;
        }
        return node == null ? mapper.nullNode() : node.deepCopy();
    }

    private boolean isReferenceNode(JsonNode node) {
        return node != null && node.isObject()
            && node.size() == 1 && node.path("$ref").isTextual();
    }

    private DataLineageRecord lineage(
        ResultReference reference,
        String targetTaskId,
        String targetPath,
        JsonNode value
    ) {
        String serialized;
        try {
            serialized = mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            serialized = value.asText("");
        }
        return new DataLineageRecord(
            reference.taskId(), reference.path(), targetTaskId, targetPath,
            "sha256:" + sha256(serialized), value.getNodeType().name(),
            serialized.length(), Instant.now());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
