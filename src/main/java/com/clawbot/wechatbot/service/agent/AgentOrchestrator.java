package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 外层 Agent 循环：规划任务、检索处理器、处理依赖、并行执行并汇总文字及附件。
 *
 * <p>CHAT_TOOL 任务内部仍由 DeepSeekChatService 完成 function-calling 内循环。</p>
 */
public final class AgentOrchestrator implements AutoCloseable {
    private static final String SUPPORTING_CONTEXT_MARKER = "\n用户问题：";

    private final ChatService fallbackChatService;
    private final TaskPlanner planner;
    private final List<AgentTaskHandler> handlers;
    private final boolean enabled;
    private final int maxOuterRounds;
    private final ExecutorService executor;

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxParallelism
    ) {
        this.fallbackChatService = fallbackChatService;
        this.planner = planner;
        this.handlers = List.copyOf(handlers);
        this.enabled = enabled;
        this.maxOuterRounds = Math.max(1, maxOuterRounds);
        this.executor = Executors.newFixedThreadPool(
            Math.max(1, maxParallelism),
            runnable -> {
                Thread thread = new Thread(runnable, "agent-outer-loop");
                thread.setDaemon(true);
                return thread;
            });
    }

    public AgentResponse execute(String userText, String history) throws Exception {
        if (!enabled || userText == null || userText.isBlank()) {
            return AgentResponse.text(fallbackChatService.chat(userText, history));
        }

        PlanningInput input = splitSupportingContext(userText);
        List<AgentTask> tasks;
        try {
            tasks = planner.plan(input.userQuestion());
        } catch (Exception planningFailure) {
            System.err.println("[WARN] Agent 任务规划失败，回退单任务流程: "
                + safeMessage(planningFailure));
            return AgentResponse.text(fallbackChatService.chat(userText, history));
        }
        if (tasks.isEmpty()) {
            return AgentResponse.text(fallbackChatService.chat(userText, history));
        }

        // 单一文本任务走原始输入，避免规划器改写造成语义或附加上下文丢失。
        if (tasks.size() == 1
            && tasks.get(0).type() == AgentTaskType.CHAT_TOOL
            && tasks.get(0).dependencies().isEmpty()) {
            return AgentResponse.text(fallbackChatService.chat(userText, history));
        }

        Map<String, AgentTask> pending = new LinkedHashMap<>();
        tasks.stream()
            .sorted(Comparator.comparingInt(AgentTask::order))
            .forEach(task -> pending.put(task.id(), task));
        Map<String, AgentTaskResult> completed = new LinkedHashMap<>();

        for (int round = 0; round < maxOuterRounds && !pending.isEmpty(); round++) {
            List<AgentTask> ready = pending.values().stream()
                .filter(task -> completed.keySet().containsAll(task.dependencies()))
                .toList();
            if (ready.isEmpty()) break;

            List<AgentTaskResult> roundResults = executeReadyTasks(
                ready, completed, input.supportingContext(), history);
            for (AgentTaskResult result : roundResults) {
                pending.remove(result.task().id());
                completed.put(result.task().id(), result);
            }
        }

        // 循环达到上限、依赖不存在或出现依赖环时，不静默丢弃任务。
        for (AgentTask task : pending.values()) {
            completed.put(task.id(), AgentTaskResult.failure(
                task, "任务依赖无法满足或超过外循环次数限制"));
        }

        return aggregate(tasks, completed);
    }

    private List<AgentTaskResult> executeReadyTasks(
        List<AgentTask> ready,
        Map<String, AgentTaskResult> completed,
        String supportingContext,
        String history
    ) {
        List<CompletableFuture<AgentTaskResult>> futures = new ArrayList<>();
        for (AgentTask task : ready) {
            futures.add(CompletableFuture.supplyAsync(
                () -> executeTask(task, completed, supportingContext, history),
                executor));
        }

        List<AgentTaskResult> results = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            try {
                results.add(futures.get(index).join());
            } catch (CompletionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                results.add(AgentTaskResult.failure(
                    ready.get(index), "处理失败：" + safeMessage(cause)));
            }
        }
        results.sort(Comparator.comparingInt(result -> result.task().order()));
        return results;
    }

    private AgentTaskResult executeTask(
        AgentTask task,
        Map<String, AgentTaskResult> completed,
        String supportingContext,
        String history
    ) {
        for (String dependencyId : task.dependencies()) {
            AgentTaskResult dependency = completed.get(dependencyId);
            if (dependency == null) {
                return AgentTaskResult.failure(task, "缺少前置任务结果：" + dependencyId);
            }
            if (!dependency.succeeded()) {
                return AgentTaskResult.failure(
                    task, "前置任务失败：" + dependency.task().instruction());
            }
        }

        AgentTaskHandler handler = handlers.stream()
            .filter(candidate -> candidate.supports(task.type()))
            .findFirst()
            .orElse(null);
        if (handler == null) {
            return AgentTaskResult.failure(task, "没有可处理该任务类型的处理器：" + task.type());
        }

        Map<String, AgentTaskResult> dependencies = new LinkedHashMap<>();
        task.dependencies().forEach(id -> dependencies.put(id, completed.get(id)));
        try {
            return handler.execute(
                task,
                new AgentTaskContext(history, supportingContext, dependencies));
        } catch (Exception error) {
            return AgentTaskResult.failure(task, "处理失败：" + safeMessage(error));
        }
    }

    private AgentResponse aggregate(
        List<AgentTask> tasks,
        Map<String, AgentTaskResult> completed
    ) {
        List<AgentTaskResult> results = tasks.stream()
            .sorted(Comparator.comparingInt(AgentTask::order))
            .map(task -> completed.getOrDefault(
                task.id(), AgentTaskResult.failure(task, "任务未执行")))
            .toList();
        List<AgentAttachment> attachments = results.stream()
            .flatMap(result -> result.attachments().stream())
            .toList();

        if (results.size() == 1) {
            AgentTaskResult result = results.get(0);
            return new AgentResponse(
                result.succeeded() ? result.text() : result.error(),
                attachments);
        }

        StringBuilder reply = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            AgentTaskResult result = results.get(index);
            if (index > 0) reply.append("\n\n");
            reply.append(index + 1).append(". 【")
                .append(compactLabel(result.task().instruction()))
                .append("】\n")
                .append(result.succeeded() ? result.text() : result.error());
        }
        return new AgentResponse(reply.toString(), attachments);
    }

    private String compactLabel(String instruction) {
        String label = instruction.replaceAll("\\s+", " ").trim();
        return label.length() <= 36 ? label : label.substring(0, 36) + "…";
    }

    private PlanningInput splitSupportingContext(String input) {
        int markerIndex = input.lastIndexOf(SUPPORTING_CONTEXT_MARKER);
        if (markerIndex < 0) return new PlanningInput("", input);
        String context = input.substring(0, markerIndex);
        String question = input.substring(
            markerIndex + SUPPORTING_CONTEXT_MARKER.length()).trim();
        if (question.isEmpty()) return new PlanningInput("", input);
        return new PlanningInput(context, question);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }

    public boolean isConfigured() {
        return fallbackChatService.isConfigured();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record PlanningInput(String supportingContext, String userQuestion) {
    }
}
