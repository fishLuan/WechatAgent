package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.agent.guard.AgentExecutionGuard;
import com.clawbot.wechatbot.service.agent.validation.ToolValidationPipeline;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.longform.LongFormGenerationPolicy;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.OptionalInt;
import java.util.Set;

/** 负责对话和 function-calling 流程，不再承担 HTTP 或具体工具执行细节。 */
public class DeepSeekChatService implements ChatService {
    private static final String TOOL_VALIDATION_PROMPT = """

        工具串联执行规则：调用工具前先依据用户原始需求明确当前步骤目标和验收条件。
        后续工具只能使用已经返回到 messages 中且 success=true 的工具结果，禁止猜测、补写或改写其中的标识、日期、地点、币种等关键字段。
        如果工具结果 success=false 或 verified=false，必须遵循 action：RETRY 表示修正参数后重试当前步骤；REPLAN 表示重新规划当前步骤或更换工具；ABORT 表示停止依赖该结果的后续步骤。
        被标记 discarded_untrusted_result=true 的结果绝不能作为后续工具参数；无法获得可信前置结果时，应明确告知用户失败原因，不得沿错误结果继续执行。
        """;
    private final DeepSeekClient client;
    private final FunctionToolRegistry toolRegistry;
    private final String systemPrompt;
    private final int maxToolRounds;
    private final ObjectMapper mapper;
    private final AgentExecutionGuard executionGuard;
    private final LongFormGenerationPolicy longFormPolicy;
    private final ToolValidationPipeline validationPipeline;

    public DeepSeekChatService(DeepSeekClient client, FunctionToolRegistry toolRegistry,
                               String systemPrompt, int maxToolRounds,
                               AgentExecutionGuard executionGuard) {
        this(client, toolRegistry, systemPrompt, maxToolRounds, executionGuard,
            LongFormGenerationPolicy.disabled(),
            new ToolValidationPipeline(client.mapper(), java.util.List.of(), 0.6D));
    }

    public DeepSeekChatService(DeepSeekClient client, FunctionToolRegistry toolRegistry,
                               String systemPrompt, int maxToolRounds,
                               AgentExecutionGuard executionGuard,
                               LongFormGenerationPolicy longFormPolicy) {
        this(client, toolRegistry, systemPrompt, maxToolRounds, executionGuard,
            longFormPolicy,
            new ToolValidationPipeline(client.mapper(), java.util.List.of(), 0.6D));
    }

    public DeepSeekChatService(DeepSeekClient client, FunctionToolRegistry toolRegistry,
                               String systemPrompt, int maxToolRounds,
                               AgentExecutionGuard executionGuard,
                               LongFormGenerationPolicy longFormPolicy,
                               ToolValidationPipeline validationPipeline) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.systemPrompt = systemPrompt;
        this.maxToolRounds = maxToolRounds;
        this.mapper = client.mapper();
        this.executionGuard = executionGuard;
        this.longFormPolicy = longFormPolicy;
        this.validationPipeline = validationPipeline;
    }

    @Override
    public String chat(String userText, String history) throws Exception {
        return chatWithAllowedTools(userText, history, null);
    }

    @Override
    public String chatWithAllowedTools(
        String userText, String history, Set<String> allowedTools
    ) throws Exception {
        Set<String> effectiveTools = expandSupportingTools(allowedTools);
        try (AgentExecutionGuard.ChatScope ignored = executionGuard.enterChat()) {
            return runToolLoop(userText, history,
                effectiveTools);
        }
    }

    private Set<String> expandSupportingTools(Set<String> allowedTools) {
        if (allowedTools == null) return null;
        java.util.LinkedHashSet<String> expanded =
            new java.util.LinkedHashSet<>(allowedTools);
        // 兼容仍选择先读取当前时间的模型；实际相对时间由调度器自行计算。
        if (expanded.contains("scheduler_manage")) {
            expanded.add("get_current_time");
        }
        return Set.copyOf(expanded);
    }

    private String runToolLoop(
        String userText, String history, Set<String> allowedTools
    ) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        boolean toolsEnabled = allowedTools == null || !allowedTools.isEmpty();
        messages.add(message("system", systemPrompt
            + (toolsEnabled ? TOOL_VALIDATION_PROMPT : "")));
        appendHistory(messages, history);
        messages.add(message("user", userText));

        for (int round = 0; round <= maxToolRounds; round++) {
            checkInterrupted();
            executionGuard.checkDeadline();
            ArrayNode availableTools = executionGuard.forceFinalResponse()
                ? mapper.createArrayNode()
                : toolRegistry.definitionsIncluding(
                    allowedTools, executionGuard.circuitOpenTools());
            JsonNode response = client.chat(messages, availableTools);
            checkInterrupted();
            executionGuard.checkDeadline();
            JsonNode assistant = response.path("choices").path(0).path("message");
            if (assistant.isMissingNode()) throw new Exception("模型响应中缺少 choices[0].message");

            JsonNode toolCalls = assistant.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = assistant.path("content").asText("").trim();
                if (content.isEmpty()) throw new Exception("模型未返回文本内容");
                return completeLongForm(
                    messages, response, assistant, content, userText);
            }
            if (executionGuard.forceFinalResponse()) {
                throw new Exception("Agent 已禁止继续调用工具，但模型仍返回了 tool_calls");
            }
            if (round == maxToolRounds) throw new Exception("工具调用次数超过限制");
            executionGuard.validateRoundToolCallCount(toolCalls.size());

            messages.add(assistant.deepCopy());
            for (JsonNode call : toolCalls) {
                checkInterrupted();
                String callId = call.path("id").asText();
                String toolName = call.path("function").path("name").asText();
                String arguments = call.path("function").path("arguments").asText("{}");
                if (allowedTools != null && !allowedTools.contains(toolName)) {
                    throw new Exception("模型尝试调用当前任务未授权的工具：" + toolName);
                }
                AgentExecutionGuard.ToolCallDecision decision =
                    executionGuard.beforeTool(toolName, arguments);
                String toolContent;
                boolean completed = false;
                try {
                    ToolExecutionOutcome outcome = decision.execute()
                        ? toolRegistry.executeWithOutcome(toolName, arguments)
                        : decision.blockedOutcome();
                    checkInterrupted();
                    if (decision.execute()) {
                        outcome = validationPipeline.validate(
                            userText, toolName, arguments, outcome,
                            executionGuard.verifiedResults()).outcome();
                    }
                    toolContent = executionGuard.completeTool(decision, outcome);
                    completed = true;
                } finally {
                    if (!completed) executionGuard.abortTool(decision);
                }
                ObjectNode toolMessage = message("tool", toolContent);
                toolMessage.put("tool_call_id", callId);
                toolMessage.put("name", toolName);
                messages.add(toolMessage);
            }
        }
        throw new Exception("工具调用流程异常结束");
    }

    private String completeLongForm(
        ArrayNode messages,
        JsonNode initialResponse,
        JsonNode initialAssistant,
        String initialContent,
        String userText
    ) throws Exception {
        OptionalInt target = longFormPolicy.targetChars(userText);
        StringBuilder result = new StringBuilder(initialContent);
        String finishReason = finishReason(initialResponse);
        JsonNode assistant = initialAssistant;

        for (int continuationRound = 0;
             shouldContinue(result, target, finishReason)
                 && continuationRound < longFormPolicy.maxContinuationRounds()
                 && result.length() < longFormPolicy.maxTotalChars();
             continuationRound++) {
            checkInterrupted();
            executionGuard.checkDeadline();
            messages.add(assistant.deepCopy());
            messages.add(message(
                "user",
                continuationPrompt(
                    result.length(),
                    target,
                    continuationRound + 1 == longFormPolicy.maxContinuationRounds())));

            JsonNode response = client.chat(messages, mapper.createArrayNode());
            checkInterrupted();
            executionGuard.checkDeadline();
            assistant = response.path("choices").path(0).path("message");
            if (assistant.isMissingNode()) {
                throw new Exception("长文续写响应中缺少 choices[0].message");
            }
            String continuation = assistant.path("content").asText("").trim();
            if (continuation.isEmpty()) {
                throw new Exception("模型未返回长文续写内容");
            }
            appendWithoutDuplicateOverlap(result, continuation);
            finishReason = finishReason(response);
        }

        String completed = limitAtSentenceBoundary(
            result.toString(), longFormPolicy.maxTotalChars());
        if ("length".equals(finishReason)
            || (target.isPresent() && !endsWithSentenceEnd(completed))) {
            completed = trimIncompleteTail(completed);
        }
        return completed.trim();
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("用户已取消当前Agent任务");
        }
    }

    private boolean shouldContinue(
        StringBuilder current, OptionalInt target, String finishReason
    ) {
        if (!longFormPolicy.enabled()) return false;
        if ("length".equals(finishReason)) return true;
        return target.isPresent()
            && (current.length() < longFormPolicy.lowerBound(target.getAsInt())
                || !endsWithSentenceEnd(current));
    }

    private String continuationPrompt(
        int currentChars, OptionalInt target, boolean finalRound
    ) {
        StringBuilder prompt = new StringBuilder(
            "请紧接上一段最后一个字继续写，只输出续写正文。"
                + "不要重复已有内容，不要添加“续写”“以下是”等说明。");
        if (target.isPresent()) {
            int remaining = Math.max(0, target.getAsInt() - currentChars);
            prompt.append("原要求约")
                .append(target.getAsInt())
                .append("字，目前约")
                .append(currentChars)
                .append("字，还需约")
                .append(remaining)
                .append("字。");
        }
        if (finalRound) {
            prompt.append("这是最后一次续写，请确保情节完整，并用完整句子自然收尾。");
        } else {
            prompt.append("保持人物、语气和情节连贯；接近目标字数时自然收尾。");
        }
        return prompt.toString();
    }

    private String finishReason(JsonNode response) {
        return response.path("choices").path(0).path("finish_reason").asText("");
    }

    private void appendWithoutDuplicateOverlap(
        StringBuilder result, String continuation
    ) {
        int maxOverlap = Math.min(
            300, Math.min(result.length(), continuation.length()));
        int overlap = 0;
        for (int length = maxOverlap; length >= 8; length--) {
            if (result.substring(result.length() - length)
                .equals(continuation.substring(0, length))) {
                overlap = length;
                break;
            }
        }
        String addition = continuation.substring(overlap);
        if (addition.isBlank()) return;
        char last = result.charAt(result.length() - 1);
        char first = addition.charAt(0);
        if (isSentenceEnd(last) || first == '\n') result.append("\n\n");
        result.append(addition);
    }

    private String limitAtSentenceBoundary(String content, int maxChars) {
        if (content.length() <= maxChars) return content;
        int boundary = lastSentenceBoundary(content, maxChars);
        return boundary >= maxChars * 3 / 4
            ? content.substring(0, boundary + 1)
            : content.substring(0, maxChars);
    }

    private String trimIncompleteTail(String content) {
        if (content.isEmpty() || endsWithSentenceEnd(content)) {
            return content;
        }
        int boundary = lastSentenceBoundary(content, content.length() - 1);
        return boundary >= content.length() / 2
            ? content.substring(0, boundary + 1)
            : content;
    }

    private int lastSentenceBoundary(String content, int fromIndex) {
        for (int index = Math.min(fromIndex, content.length() - 1);
             index >= 0;
             index--) {
            if (isSentenceEnd(content.charAt(index))) return index;
        }
        return -1;
    }

    private boolean endsWithSentenceEnd(CharSequence content) {
        for (int index = content.length() - 1; index >= 0; index--) {
            char value = content.charAt(index);
            if (Character.isWhitespace(value) || isClosingPunctuation(value)) continue;
            return isSentenceEnd(value);
        }
        return false;
    }

    private boolean isClosingPunctuation(char value) {
        return value == '”' || value == '’' || value == '"' || value == '\''
            || value == '）' || value == ')' || value == '】' || value == ']'
            || value == '》' || value == '〉' || value == '」' || value == '』';
    }

    private boolean isSentenceEnd(char value) {
        return value == '。' || value == '！' || value == '？'
            || value == '.' || value == '!' || value == '?';
    }

    private void appendHistory(ArrayNode messages, String history) throws Exception {
        if (history == null || history.isBlank()) return;
        JsonNode parsed = mapper.readTree("[" + history + "]");
        if (parsed.isArray()) parsed.forEach(messages::add);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }
}
