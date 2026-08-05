package com.clawbot.wechatbot.service.agent.guard;

import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 为一次根 chat 调用维护深度、调用路径、重复调用、失败熔断和内容预算。
 *
 * <p>当前工具调用是同步执行的，因此上下文通过 ThreadLocal 在嵌套 chat 调用间继承。
 * 工具不得把 ChatService 调用转移到未受管理的线程。</p>
 */
public final class AgentExecutionGuard {
    private final AgentGuardPolicy policy;
    private final ObjectMapper mapper;
    private final ThreadLocal<ExecutionState> stateHolder = new ThreadLocal<>();

    public AgentExecutionGuard(AgentGuardPolicy policy, ObjectMapper mapper) {
        this.policy = policy;
        this.mapper = mapper;
    }

    public ChatScope enterChat() throws AgentSafetyException {
        ExecutionState state = stateHolder.get();
        if (state == null) {
            state = new ExecutionState(System.nanoTime() + policy.executionTimeout().toNanos());
            stateHolder.set(state);
        }
        checkDeadline(state);
        if (state.chatDepth >= policy.maxChatDepth()) {
            if (state.chatDepth == 0) stateHolder.remove();
            throw new AgentSafetyException(
                "CHAT_DEPTH_EXCEEDED",
                "嵌套 chat() 深度超过 " + policy.maxChatDepth() + "，已终止调用");
        }
        state.chatDepth++;
        return new ChatScope(state);
    }

    public void checkDeadline() throws AgentSafetyException {
        checkDeadline(requiredState());
    }

    public void validateRoundToolCallCount(int count) throws AgentSafetyException {
        if (count > policy.maxToolCallsPerRound()) {
            throw new AgentSafetyException(
                "TOO_MANY_TOOL_CALLS_IN_ROUND",
                "模型单轮请求了 " + count + " 个工具，超过上限 "
                    + policy.maxToolCallsPerRound());
        }
    }

    public ToolCallDecision beforeTool(String toolName, String rawArguments)
        throws AgentSafetyException {
        ExecutionState state = requiredState();
        checkDeadline(state);
        String safeName = toolName == null ? "" : toolName.trim();
        ToolCallKey key = new ToolCallKey(
            safeName, fingerprint(safeName, rawArguments));

        if (state.activeToolNames.contains(safeName)) {
            state.forceFinalResponse = true;
            return ToolCallDecision.blocked(
                key,
                blockedOutcome(
                    "RECURSIVE_TOOL_CALL",
                    "检测到工具递归调用：" + safeName));
        }

        String previousResult = state.successfulResults.get(key);
        if (previousResult != null) {
            state.forceFinalResponse = true;
            return ToolCallDecision.blocked(
                key,
                blockedOutcome(
                    "DUPLICATE_TOOL_CALL",
                    "相同工具和参数已经成功执行过，禁止重复调用"));
        }

        if (state.circuitOpenTools.contains(safeName)) {
            return ToolCallDecision.blocked(
                key,
                blockedOutcome(
                    "TOOL_CIRCUIT_OPEN",
                    "工具连续失败达到上限，本次请求内已熔断：" + safeName));
        }

        if (state.totalToolCalls >= policy.maxTotalToolCalls()) {
            state.forceFinalResponse = true;
            return ToolCallDecision.blocked(
                key,
                blockedOutcome(
                    "TOTAL_TOOL_CALL_LIMIT_EXCEEDED",
                    "工具调用总数超过 " + policy.maxTotalToolCalls()));
        }

        state.totalToolCalls++;
        state.activeToolNames.push(safeName);
        return ToolCallDecision.allowed(key);
    }

    public String completeTool(
        ToolCallDecision decision, ToolExecutionOutcome outcome
    ) throws AgentSafetyException {
        ExecutionState state = requiredState();
        if (!decision.execute()) return decision.blockedOutcome().content();

        popActiveTool(state, decision.key().toolName());
        checkDeadline(state);

        String limitedContent = limitSingleResult(outcome);
        if (state.totalToolResultChars + limitedContent.length()
            > policy.maxTotalToolResultChars()) {
            state.forceFinalResponse = true;
            String budgetError = errorJson(
                "TOOL_CONTENT_BUDGET_EXCEEDED",
                "累计工具结果超过 " + policy.maxTotalToolResultChars()
                    + " 字，已停止继续调用工具",
                false);
            state.totalToolResultChars = policy.maxTotalToolResultChars();
            return budgetError;
        }
        state.totalToolResultChars += limitedContent.length();

        if (outcome.hasUsableContent()) {
            state.successfulResults.put(decision.key(), limitedContent);
            state.consecutiveFailures.remove(decision.key().toolName());
        } else {
            int failures = state.consecutiveFailures.merge(
                decision.key().toolName(), 1, Integer::sum);
            if (failures >= policy.maxSameToolFailures()) {
                state.circuitOpenTools.add(decision.key().toolName());
            }
        }
        if (state.totalToolCalls >= policy.maxTotalToolCalls()) {
            state.forceFinalResponse = true;
        }
        return limitedContent;
    }

    public void abortTool(ToolCallDecision decision) {
        if (decision == null || !decision.execute()) return;
        ExecutionState state = stateHolder.get();
        if (state != null) popActiveTool(state, decision.key().toolName());
    }

    public boolean forceFinalResponse() {
        ExecutionState state = stateHolder.get();
        return state != null && state.forceFinalResponse;
    }

    public Set<String> circuitOpenTools() {
        ExecutionState state = stateHolder.get();
        return state == null ? Set.of() : Set.copyOf(state.circuitOpenTools);
    }

    /** 仅返回已经通过校验并正式提交的工具结果，供后续校验器做跨步骤检查。 */
    public Map<String, String> verifiedResults() {
        ExecutionState state = stateHolder.get();
        if (state == null) return Map.of();
        Map<String, String> results = new HashMap<>();
        state.successfulResults.forEach((key, value) ->
            results.put(key.toolName() + ":" + key.argumentsFingerprint(), value));
        return Map.copyOf(results);
    }

    private String limitSingleResult(ToolExecutionOutcome outcome) {
        String content = outcome.content();
        if (content.length() <= policy.maxToolResultChars()) return content;

        int previewLength = Math.max(64, policy.maxToolResultChars() - 512);
        while (previewLength > 32) {
            ObjectNode truncated = mapper.createObjectNode();
            truncated.put("success", outcome.success());
            truncated.put("truncated", true);
            truncated.put("code", "TOOL_RESULT_TRUNCATED");
            truncated.put("retryable", false);
            truncated.put("originalChars", content.length());
            truncated.put("contentPreview", content.substring(0,
                Math.min(previewLength, content.length())));
            String json = writeJson(truncated);
            if (json.length() <= policy.maxToolResultChars()) return json;
            previewLength = previewLength * 3 / 4;
        }
        return errorJson(
            "TOOL_RESULT_TRUNCATED",
            "工具结果超过 " + policy.maxToolResultChars() + " 字，且无法安全生成预览",
            false);
    }

    private ToolExecutionOutcome blockedOutcome(String code, String message) {
        return new ToolExecutionOutcome(
            errorJson(code, message, false), false, false, code);
    }

    private String errorJson(String code, String message, boolean retryable) {
        ObjectNode error = mapper.createObjectNode();
        error.put("success", false);
        error.put("code", code);
        error.put("retryable", retryable);
        error.put("error", message);
        return writeJson(error);
    }

    private String fingerprint(String toolName, String rawArguments) {
        try {
            JsonNode parsed = mapper.readTree(
                rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
            String canonical = mapper.writeValueAsString(canonicalize(parsed));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (toolName + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            return Integer.toHexString((toolName + "\n" + rawArguments).hashCode());
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = mapper.createObjectNode();
        Map<String, JsonNode> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        fields.forEachRemaining(entry -> sorted.put(
            entry.getKey(), canonicalize(entry.getValue())));
        sorted.forEach(object::set);
        return object;
    }

    private void checkDeadline(ExecutionState state) throws AgentSafetyException {
        if (System.nanoTime() >= state.deadlineNanos) {
            state.forceFinalResponse = true;
            throw new AgentSafetyException(
                "AGENT_EXECUTION_TIMEOUT",
                "Agent 执行时间超过 " + policy.executionTimeout().toSeconds() + " 秒");
        }
    }

    private ExecutionState requiredState() {
        ExecutionState state = stateHolder.get();
        if (state == null) throw new IllegalStateException("Agent 执行上下文尚未建立");
        return state;
    }

    private void popActiveTool(ExecutionState state, String toolName) {
        if (!state.activeToolNames.isEmpty()
            && state.activeToolNames.peek().equals(toolName)) {
            state.activeToolNames.pop();
        } else {
            state.activeToolNames.removeFirstOccurrence(toolName);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return "{\"success\":false,\"retryable\":false}";
        }
    }

    public record ToolCallDecision(
        ToolCallKey key,
        boolean execute,
        ToolExecutionOutcome blockedOutcome
    ) {
        private static ToolCallDecision allowed(ToolCallKey key) {
            return new ToolCallDecision(key, true, null);
        }

        private static ToolCallDecision blocked(
            ToolCallKey key, ToolExecutionOutcome outcome
        ) {
            return new ToolCallDecision(key, false, outcome);
        }
    }

    public record ToolCallKey(String toolName, String argumentsFingerprint) {
    }

    public final class ChatScope implements AutoCloseable {
        private final ExecutionState state;
        private boolean closed;

        private ChatScope(ExecutionState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            state.chatDepth--;
            if (state.chatDepth <= 0) stateHolder.remove();
        }
    }

    private static final class ExecutionState {
        private final long deadlineNanos;
        private final Deque<String> activeToolNames = new ArrayDeque<>();
        private final Map<ToolCallKey, String> successfulResults = new HashMap<>();
        private final Map<String, Integer> consecutiveFailures = new HashMap<>();
        private final Set<String> circuitOpenTools = new HashSet<>();
        private int chatDepth;
        private int totalToolCalls;
        private int totalToolResultChars;
        private boolean forceFinalResponse;

        private ExecutionState(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }
}
