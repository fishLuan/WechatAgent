package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final Duration executionTimeout;
    private final ExecutorService executor;
    private final AgentRequestContextHolder requestContextHolder;

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxParallelism
    ) {
        this(
            fallbackChatService,
            planner,
            handlers,
            enabled,
            maxOuterRounds,
            maxParallelism,
            Duration.ofSeconds(90),
            new AgentRequestContextHolder());
    }

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxParallelism,
        Duration executionTimeout
    ) {
        this(
            fallbackChatService,
            planner,
            handlers,
            enabled,
            maxOuterRounds,
            maxParallelism,
            executionTimeout,
            new AgentRequestContextHolder());
    }

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxParallelism,
        Duration executionTimeout,
        AgentRequestContextHolder requestContextHolder
    ) {
        this.fallbackChatService = fallbackChatService;
        this.planner = planner;
        this.handlers = List.copyOf(handlers);
        this.requestContextHolder = java.util.Objects.requireNonNull(
            requestContextHolder, "requestContextHolder");
        this.enabled = enabled;
        this.maxOuterRounds = Math.max(1, maxOuterRounds);
        if (executionTimeout == null
            || executionTimeout.isZero()
            || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("Agent 执行超时必须大于 0");
        }
        this.executionTimeout = executionTimeout;
        this.executor = Executors.newFixedThreadPool(
            Math.max(1, maxParallelism),
            runnable -> {
                Thread thread = new Thread(runnable, "agent-outer-loop");
                thread.setDaemon(true);
                return thread;
            });
    }

    public AgentResponse execute(String userText, String history) throws Exception {
        return execute(userText, history, AgentRequestContext.anonymous());
    }

    public AgentResponse execute(
        String userText,
        String history,
        AgentRequestContext requestContext
    ) throws Exception {
        return execute(userText, history, requestContext, List.of());
    }

    public AgentResponse execute(
        String userText,
        String history,
        AgentRequestContext requestContext,
        List<AgentInputAttachment> inputAttachments
    ) throws Exception {
        AgentRequestContext actualContext = requestContext == null
            ? AgentRequestContext.anonymous()
            : requestContext;
        List<AgentInputAttachment> actualAttachments = inputAttachments == null
            ? List.of()
            : List.copyOf(inputAttachments);
        long deadlineNanos = System.nanoTime() + executionTimeout.toNanos();
        if (!enabled || userText == null || userText.isBlank()) {
            return executeFallback(
                userText, history, deadlineNanos, actualContext);
        }

        PlanningInput input = splitSupportingContext(userText);
        List<AgentTask> tasks;
        try {
            tasks = planner.plan(input.userQuestion());
        } catch (Exception planningFailure) {
            System.err.println("[WARN] Agent 任务规划失败，回退单任务流程: "
                + safeMessage(planningFailure));
            return executeFallback(
                userText, history, deadlineNanos, actualContext);
        }
        if (tasks.isEmpty()) {
            return executeFallback(
                userText, history, deadlineNanos, actualContext);
        }
        return executeTasks(
            userText,
            history,
            input,
            tasks,
            deadlineNanos,
            actualContext,
            actualAttachments);
    }

    /**
     * 执行消息入口已经生成的任务计划，避免普通文本处理器再次调用规划模型。
     */
    public AgentResponse executePlanned(
        String userText,
        String history,
        List<AgentTask> preplannedTasks
    ) throws Exception {
        return executePlanned(
            userText,
            history,
            preplannedTasks,
            AgentRequestContext.anonymous());
    }

    public AgentResponse executePlanned(
        String userText,
        String history,
        List<AgentTask> preplannedTasks,
        AgentRequestContext requestContext
    ) throws Exception {
        return executePlanned(
            userText,
            history,
            preplannedTasks,
            requestContext,
            List.of());
    }

    public AgentResponse executePlanned(
        String userText,
        String history,
        List<AgentTask> preplannedTasks,
        AgentRequestContext requestContext,
        List<AgentInputAttachment> inputAttachments
    ) throws Exception {
        AgentRequestContext actualContext = requestContext == null
            ? AgentRequestContext.anonymous()
            : requestContext;
        List<AgentInputAttachment> actualAttachments = inputAttachments == null
            ? List.of()
            : List.copyOf(inputAttachments);
        long deadlineNanos = System.nanoTime() + executionTimeout.toNanos();
        if (!enabled || userText == null || userText.isBlank()
            || preplannedTasks == null || preplannedTasks.isEmpty()) {
            return executeFallback(
                userText, history, deadlineNanos, actualContext);
        }
        return executeTasks(
            userText,
            history,
            splitSupportingContext(userText),
            List.copyOf(preplannedTasks),
            deadlineNanos,
            actualContext,
            actualAttachments);
    }

    private AgentResponse executeTasks(
        String userText,
        String history,
        PlanningInput input,
        List<AgentTask> tasks,
        long deadlineNanos,
        AgentRequestContext requestContext,
        List<AgentInputAttachment> inputAttachments
    ) throws Exception {

        // 单一文本任务走原始输入，避免规划器改写造成语义或附加上下文丢失。
        if (tasks.size() == 1
            && tasks.get(0).type() == AgentTaskType.CHAT_TOOL
            && tasks.get(0).dependencies().isEmpty()
            && inputAttachments.isEmpty()) {
            return executeFallback(
                userText, history, deadlineNanos, requestContext);
        }

        Map<String, AgentTask> pending = new LinkedHashMap<>();
        tasks.stream()
            .sorted(Comparator.comparingInt(AgentTask::order))
            .forEach(task -> pending.put(task.id(), task));
        Map<String, AgentTaskResult> completed = new LinkedHashMap<>();

        for (int round = 0;
             round < maxOuterRounds
                 && !pending.isEmpty()
                 && System.nanoTime() < deadlineNanos;
             round++) {
            List<AgentTask> ready = pending.values().stream()
                .filter(task -> completed.keySet().containsAll(task.dependencies()))
                .toList();
            if (ready.isEmpty()) break;

            List<AgentTaskResult> roundResults = executeReadyTasks(
                ready,
                completed,
                input.supportingContext(),
                history,
                deadlineNanos,
                requestContext,
                inputAttachments);
            for (AgentTaskResult result : roundResults) {
                pending.remove(result.task().id());
                completed.put(result.task().id(), result);
            }
        }

        // 循环达到上限、依赖不存在或出现依赖环时，不静默丢弃任务。
        boolean timedOut = System.nanoTime() >= deadlineNanos;
        for (AgentTask task : pending.values()) {
            completed.put(task.id(), AgentTaskResult.failure(
                task,
                timedOut
                    ? "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒"
                    : "任务依赖无法满足或超过外循环次数限制"));
        }

        return aggregate(tasks, completed);
    }

    private AgentResponse executeFallback(
        String userText,
        String history,
        long deadlineNanos,
        AgentRequestContext requestContext
    ) throws Exception {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException(
                "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒");
        }
        Future<String> future = executor.submit(
            () -> requestContextHolder.callWith(
                requestContext,
                () -> fallbackChatService.chat(userText, history)));
        try {
            return AgentResponse.text(
                future.get(remainingNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new TimeoutException(
                "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new Exception("Agent 单任务执行失败", cause);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Exception("Agent 执行被中断", error);
        }
    }

    private List<AgentTaskResult> executeReadyTasks(
        List<AgentTask> ready,
        Map<String, AgentTaskResult> completed,
        String supportingContext,
        String history,
        long deadlineNanos,
        AgentRequestContext requestContext,
        List<AgentInputAttachment> inputAttachments
    ) {
        List<Future<AgentTaskResult>> futures = new ArrayList<>();
        for (AgentTask task : ready) {
            futures.add(executor.submit(
                () -> requestContextHolder.callWith(
                    requestContext,
                    () -> executeTask(
                        task,
                        completed,
                        supportingContext,
                        history,
                        inputAttachments))));
        }

        List<AgentTaskResult> results = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                cancelRemaining(futures, index);
                addTimeoutResults(results, ready, index);
                break;
            }
            try {
                results.add(futures.get(index).get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (TimeoutException error) {
                cancelRemaining(futures, index);
                addTimeoutResults(results, ready, index);
                break;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                results.add(AgentTaskResult.failure(
                    ready.get(index), "处理失败：" + safeMessage(cause)));
            } catch (InterruptedException error) {
                cancelRemaining(futures, index);
                Thread.currentThread().interrupt();
                results.add(AgentTaskResult.failure(
                    ready.get(index), "Agent 执行被中断"));
                for (int remaining = index + 1; remaining < ready.size(); remaining++) {
                    results.add(AgentTaskResult.failure(
                        ready.get(remaining), "Agent 执行被中断"));
                }
                break;
            }
        }
        results.sort(Comparator.comparingInt(result -> result.task().order()));
        return results;
    }

    private void cancelRemaining(List<Future<AgentTaskResult>> futures, int start) {
        for (int index = start; index < futures.size(); index++) {
            futures.get(index).cancel(true);
        }
    }

    private void addTimeoutResults(
        List<AgentTaskResult> results, List<AgentTask> ready, int start
    ) {
        for (int index = start; index < ready.size(); index++) {
            results.add(AgentTaskResult.failure(
                ready.get(index),
                "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒"));
        }
    }

    private AgentTaskResult executeTask(
        AgentTask task,
        Map<String, AgentTaskResult> completed,
        String supportingContext,
        String history,
        List<AgentInputAttachment> inputAttachments
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
                new AgentTaskContext(
                    history,
                    supportingContext,
                    dependencies,
                    inputAttachments));
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
