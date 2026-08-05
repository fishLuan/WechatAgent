package com.clawbot.wechatbot.confirmation;

import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConfirmationReplyService {
    private static final Pattern ID = Pattern.compile("(?i)(CFM-[A-Z0-9]{8})");
    private final ConfirmationService confirmations;
    private final FunctionToolRegistry tools;
    private final AgentRequestContextHolder contexts;
    private final ObjectMapper mapper;

    public ConfirmationReplyService(ConfirmationService confirmations,
                                    @Lazy FunctionToolRegistry tools,
                                    AgentRequestContextHolder contexts, ObjectMapper mapper) {
        this.confirmations = confirmations; this.tools = tools; this.contexts = contexts;
        this.mapper = mapper;
    }

    public ConfirmationReply handle(String userId, Long messageId, String text) throws Exception {
        String normalized = text == null ? "" : text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean hasConfirmationId = ID.matcher(normalized).find();
        boolean confirm = lower.equals("确认") || lower.equals("同意") || lower.equals("是")
            || (hasConfirmationId && (lower.startsWith("确认") || lower.startsWith("同意")));
        boolean cancel = lower.equals("取消") || lower.equals("不执行")
            || lower.equals("拒绝") || lower.equals("否")
            || (hasConfirmationId && (lower.startsWith("取消")
                || lower.startsWith("不执行") || lower.startsWith("拒绝")));
        boolean modify = lower.startsWith("修改") || lower.startsWith("改成") || lower.contains("但是改成");
        if (!confirm && !cancel && !modify) return ConfirmationReply.notHandled();

        PendingConfirmation pending = resolve(userId, normalized);
        if (pending == null) {
            if (!ID.matcher(normalized).find()) return ConfirmationReply.notHandled();
            return new ConfirmationReply(true, false,
                "没有找到该待确认任务，可能已经完成或超过30分钟有效期。", "");
        }
        if (cancel) {
            confirmations.status(pending, ConfirmationStatus.REJECTED, "用户取消");
            return new ConfirmationReply(true, false,
                "已放弃本次操作：" + pending.getOperationSummary()
                    + "。系统没有执行任何变更。", "");
        }
        if (modify) {
            pending.setModification(normalized);
            confirmations.status(pending, ConfirmationStatus.REJECTED, "用户要求修改条件");
            String revised = "请按用户的新条件重新规划并执行。原操作：" + pending.getOperationSummary()
                + "；原工具参数：" + pending.getArgumentsJson() + "；用户修改：" + normalized;
            return new ConfirmationReply(true, true, "", revised);
        }

        confirmations.status(pending, ConfirmationStatus.CONFIRMED, "用户确认");
        confirmations.status(pending, ConfirmationStatus.RESUMING, "恢复执行");
        try {
            ToolExecutionOutcome outcome = confirmations.authorized(() ->
                contexts.callWith(new AgentRequestContext(userId, messageId),
                    () -> tools.executeWithOutcome(pending.getToolName(), pending.getArgumentsJson())));
            confirmations.status(pending, outcome.success()
                ? ConfirmationStatus.SUCCEEDED : ConfirmationStatus.FAILED, outcome.content());
            var json = mapper.readTree(outcome.content());
            String reply = json.path("message").asText(json.path("error").asText(outcome.content()));
            return new ConfirmationReply(true, false, reply, "");
        } catch (Exception exception) {
            confirmations.status(pending, ConfirmationStatus.FAILED, exception.getMessage());
            throw exception;
        }
    }

    private PendingConfirmation resolve(String userId, String text) {
        Matcher matcher = ID.matcher(text);
        if (matcher.find()) return confirmations.findForUser(matcher.group(1).toUpperCase(Locale.ROOT), userId);
        List<PendingConfirmation> waiting = confirmations.waiting(userId);
        return waiting.size() == 1 ? waiting.getFirst() : null;
    }
}
