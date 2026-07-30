package com.clawbot.wechatbot.service.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 传给任务处理器的只读执行上下文。 */
public record AgentTaskContext(
    String history,
    String supportingContext,
    Map<String, AgentTaskResult> dependencyResults,
    List<AgentInputAttachment> inputAttachments
) {
    public AgentTaskContext {
        history = history == null ? "" : history;
        supportingContext = supportingContext == null ? "" : supportingContext;
        dependencyResults = dependencyResults == null
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(dependencyResults));
        inputAttachments = inputAttachments == null
            ? List.of()
            : List.copyOf(inputAttachments);
    }

    public AgentTaskContext(
        String history,
        String supportingContext,
        Map<String, AgentTaskResult> dependencyResults
    ) {
        this(history, supportingContext, dependencyResults, List.of());
    }

    public String dependencyText() {
        StringBuilder text = new StringBuilder();
        dependencyResults.values().stream()
            .sorted(java.util.Comparator.comparingInt(result -> result.task().order()))
            .filter(AgentTaskResult::succeeded)
            .filter(result -> !result.text().isBlank())
            .forEach(result -> {
                if (text.length() > 0) text.append("\n\n");
                text.append("【").append(result.task().instruction()).append("】\n")
                    .append(result.text());
            });
        return text.toString();
    }
}
