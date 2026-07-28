package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;

import java.util.List;

/** 将文本/工具任务交给 DeepSeek function-calling 内循环。 */
public final class ChatAgentTaskHandler implements AgentTaskHandler {
    private static final String SUPPORTING_CONTEXT_MARKER = "\n用户问题：";

    private final ChatService chatService;

    public ChatAgentTaskHandler(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public boolean supports(AgentTaskType type) {
        return type == AgentTaskType.CHAT_TOOL;
    }

    @Override
    public AgentTaskResult execute(AgentTask task, AgentTaskContext context) throws Exception {
        StringBuilder input = new StringBuilder();
        if (!context.supportingContext().isBlank()) {
            input.append(context.supportingContext()).append(SUPPORTING_CONTEXT_MARKER);
        }
        input.append("请只处理下面这一项用户需求，直接给出完整答案，不要提及任务拆解过程：\n")
            .append(task.instruction());
        String dependencyText = context.dependencyText();
        if (!dependencyText.isBlank()) {
            input.append("\n\n【必须参考的前置任务结果】\n").append(dependencyText);
        }
        String answer = chatService.chat(input.toString(), context.history());
        return AgentTaskResult.success(task, answer, List.of());
    }
}
