package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.agent.acceptance.DefaultTaskAcceptanceEvaluator;
import com.clawbot.wechatbot.service.agent.acceptance.TaskAcceptanceEvaluator;
import com.clawbot.wechatbot.service.agent.acceptance.TaskEvaluation;
import com.clawbot.wechatbot.service.agent.state.AgentExecutionState;
import com.clawbot.wechatbot.service.agent.state.AgentTaskState;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import com.clawbot.wechatbot.service.agent.replan.AgentReplanPolicy;
import com.clawbot.wechatbot.service.agent.replan.NoOpTaskReplanner;
import com.clawbot.wechatbot.service.agent.replan.PlanMutationApplier;
import com.clawbot.wechatbot.service.agent.replan.ReplanRequest;
import com.clawbot.wechatbot.service.agent.replan.ReplanResult;
import com.clawbot.wechatbot.service.agent.replan.TaskReplanner;
import com.clawbot.wechatbot.service.agent.reference.DataLineageRecord;
import com.clawbot.wechatbot.service.agent.reference.ReferenceResolutionException;
import com.clawbot.wechatbot.service.agent.reference.ReferencePolicy;
import com.clawbot.wechatbot.service.agent.reference.ResolvedTaskInput;
import com.clawbot.wechatbot.service.agent.reference.ResultReferenceResolver;
import com.clawbot.wechatbot.service.agent.interrupt.AgentExecutionControlService;
import com.clawbot.wechatbot.service.agent.interrupt.AgentExecutionSession;
import com.clawbot.wechatbot.service.agent.interrupt.AgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
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
    private final int maxTasksPerBatch;
    private final Duration executionTimeout;
    private final ExecutorService executor;
    private final AgentRequestContextHolder requestContextHolder;
    private final TaskAcceptanceEvaluator acceptanceEvaluator;
    private final TaskReplanner replanner;
    private final PlanMutationApplier mutationApplier;
    private final AgentReplanPolicy replanPolicy;
    private final ResultReferenceResolver referenceResolver;
    private AgentExecutionControlService executionControl;

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
            5,
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
            5,
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
        this(
            fallbackChatService,
            planner,
            handlers,
            enabled,
            maxOuterRounds,
            5,
            maxParallelism,
            executionTimeout,
            requestContextHolder);
    }

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxTasksPerBatch,
        int maxParallelism,
        Duration executionTimeout,
        AgentRequestContextHolder requestContextHolder
    ) {
        this(fallbackChatService, planner, handlers, enabled, maxOuterRounds,
            maxTasksPerBatch, maxParallelism, executionTimeout,
            requestContextHolder,
            new DefaultTaskAcceptanceEvaluator(new ObjectMapper()));
    }

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxTasksPerBatch,
        int maxParallelism,
        Duration executionTimeout,
        AgentRequestContextHolder requestContextHolder,
        TaskAcceptanceEvaluator acceptanceEvaluator
    ) {
        this(fallbackChatService, planner, handlers, enabled, maxOuterRounds,
            maxTasksPerBatch, maxParallelism, executionTimeout,
            requestContextHolder, acceptanceEvaluator,
            new NoOpTaskReplanner(), null, AgentReplanPolicy.disabled(),
            new ResultReferenceResolver(
                new ObjectMapper(), ReferencePolicy.defaults()));
    }

    public AgentOrchestrator(
        ChatService fallbackChatService,
        TaskPlanner planner,
        List<AgentTaskHandler> handlers,
        boolean enabled,
        int maxOuterRounds,
        int maxTasksPerBatch,
        int maxParallelism,
        Duration executionTimeout,
        AgentRequestContextHolder requestContextHolder,
        TaskAcceptanceEvaluator acceptanceEvaluator,
        TaskReplanner replanner,
        PlanMutationApplier mutationApplier,
        AgentReplanPolicy replanPolicy,
        ResultReferenceResolver referenceResolver
    ) {
        this.fallbackChatService = fallbackChatService;
        this.planner = planner;
        this.handlers = List.copyOf(handlers);
        this.requestContextHolder = java.util.Objects.requireNonNull(
            requestContextHolder, "requestContextHolder");
        this.acceptanceEvaluator = java.util.Objects.requireNonNull(
            acceptanceEvaluator, "acceptanceEvaluator");
        this.replanner = java.util.Objects.requireNonNull(replanner, "replanner");
        this.mutationApplier = mutationApplier;
        this.replanPolicy = java.util.Objects.requireNonNull(
            replanPolicy, "replanPolicy");
        this.referenceResolver = java.util.Objects.requireNonNull(
            referenceResolver, "referenceResolver");
        this.enabled = enabled;
        this.maxOuterRounds = Math.max(1, maxOuterRounds);
        this.maxTasksPerBatch = Math.max(1, maxTasksPerBatch);
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

    public AgentOrchestrator enableInterrupts(AgentExecutionControlService control) {
        this.executionControl = control;
        return this;
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
        TaskPlan plan;
        try {
            plan = planner.planDetailed(input.userQuestion());
        } catch (Exception planningFailure) {
            System.err.println("[WARN] Agent 任务规划失败，回退单任务流程: "
                + safeMessage(planningFailure));
            return executeFallback(
                userText, history, deadlineNanos, actualContext);
        }
        if (plan.limitExceeded()) {
            return AgentResponse.text(plan.userMessage());
        }
        List<AgentTask> tasks = plan.tasks();
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

        AgentExecutionState state = new AgentExecutionState(userText, tasks);
        AgentExecutionSession executionSession = executionControl == null
            ? null : executionControl.begin(requestContext, userText);

        for (int round = 0;
            round < maxOuterRounds
                && state.hasUnfinishedTasks()
                && !state.aborted()
                && System.nanoTime() < deadlineNanos;
             round++) {
            if (isCancellationRequested(executionSession)) {
                state.cancelUnfinished();
                break;
            }
            state.nextOuterRound();
            if (state.totalTaskExecutions()
                >= replanPolicy.maxTotalTaskExecutions()) {
                failActionableTasks(state, "任务执行总次数超过限制");
                break;
            }

            boolean progressed = false;
            List<AgentTask> ready = state.readyTasks(maxTasksPerBatch);
            if (!ready.isEmpty()) {
                int remainingExecutions = replanPolicy.maxTotalTaskExecutions()
                    - state.totalTaskExecutions();
                ready = ready.stream().limit(Math.max(0, remainingExecutions)).toList();
                List<AgentTask> executableTasks = new ArrayList<>();
                Map<String, List<DataLineageRecord>> lineageByTask =
                    new LinkedHashMap<>();
                for (AgentTask task : ready) {
                    state.markRunning(task);
                    try {
                        ResolvedTaskInput resolved = referenceResolver.resolve(task, state);
                        state.recordLineage(resolved.lineage());
                        AgentTask executable = withInput(task, resolved.input());
                        executableTasks.add(executable);
                        lineageByTask.put(task.id(), resolved.lineage());
                    } catch (ReferenceResolutionException error) {
                        AgentTaskResult failed = AgentTaskResult.failure(
                            task, error.getMessage());
                        state.recordResult(failed, new TaskEvaluation(
                            com.clawbot.wechatbot.service.agent.acceptance.TaskDecision.REPLAN,
                            error.code(), error.getMessage(),
                            new ObjectMapper().createObjectNode(), List.of(),
                            "修正任务依赖或引用路径后重新规划"));
                    }
                }

                List<AgentTaskResult> roundResults = executeReadyTasks(
                    executableTasks,
                    state.verifiedResults(),
                    input.supportingContext(),
                    history,
                    deadlineNanos,
                    requestContext,
                    inputAttachments,
                    lineageByTask,
                    executionSession);
                if (isCancellationRequested(executionSession)) {
                    state.cancelUnfinished();
                    break;
                }
                for (AgentTaskResult result : roundResults) {
                    TaskEvaluation evaluation = acceptanceEvaluator.evaluate(
                        result.task(), result,
                        state.verifiedDependencies(result.task()));
                    state.recordResult(result, evaluation);
                }
                progressed = true;
            }

            if (isCancellationRequested(executionSession)) {
                state.cancelUnfinished();
                break;
            }
            progressed |= schedulePermittedRetries(state);
            if (!isCancellationRequested(executionSession)) {
                progressed |= executeRequiredReplan(
                    state, deadlineNanos, executionSession);
            }
            if (!progressed) break;
        }

        if (isCancellationRequested(executionSession)) {
            state.cancelUnfinished();
            boolean partial = state.hasCompletedSideEffects();
            if (executionControl != null) {
                executionControl.finish(executionSession,
                    partial ? AgentRunStatus.PARTIALLY_CANCELLED : AgentRunStatus.CANCELLED,
                    state.completedTaskIds(), state.cancelledTaskIds(), partial,
                    requestContext.userId());
            }
            return cancellationResponse(executionSession, state, partial);
        }

        boolean timedOut = System.nanoTime() >= deadlineNanos;
        state.failUnresolvedTasks(
            timedOut
                ? "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒"
                : "任务依赖未通过验收、依赖不存在或超过外循环次数限制");

        AgentResponse response = aggregate(state);
        if (executionControl != null) {
            executionControl.finish(executionSession, AgentRunStatus.SUCCEEDED,
                state.completedTaskIds(), List.of(), state.hasCompletedSideEffects(),
                requestContext.userId());
        }
        return response;
    }

    private boolean schedulePermittedRetries(AgentExecutionState state) {
        boolean changed = false;
        for (AgentTaskState taskState : state.retryPendingTaskStates()) {
            int retriesAlreadyUsed = Math.max(0, taskState.attemptCount() - 1);
            if (replanPolicy.enabled()
                && retriesAlreadyUsed < replanPolicy.maxRetriesPerTask()
                && state.totalTaskExecutions()
                    < replanPolicy.maxTotalTaskExecutions()) {
                state.scheduleRetry(taskState.task().id());
            } else if (replanPolicy.enabled()) {
                state.requireReplan(taskState.task().id());
            } else {
                continue;
            }
            changed = true;
        }
        return changed;
    }

    private boolean executeRequiredReplan(
        AgentExecutionState state, long deadlineNanos,
        AgentExecutionSession executionSession
    ) {
        List<AgentTaskState> candidates = state.replanRequiredTaskStates();
        if (candidates.isEmpty()) return false;
        AgentTaskState failed = candidates.get(0);
        if (!replanPolicy.enabled()) return false;
        if (!replanner.isConfigured() || mutationApplier == null) {
            state.failTask(failed.task().id(), "局部重规划不可用");
            return true;
        }
        if (state.replanCount() >= replanPolicy.maxReplans()) {
            state.failTask(failed.task().id(), "局部重规划次数超过限制");
            return true;
        }

        int remainingBudget = Math.max(
            0, replanPolicy.maxTotalTasks() - state.tasks().size());
        List<AgentTask> remaining = state.taskStates().stream()
            .filter(item -> item.status() != TaskStatus.VERIFIED)
            .map(AgentTaskState::task)
            .toList();
        ReplanRequest request = new ReplanRequest(
            state.originalUserRequest(), failed.task(), failed.lastResult(),
            failed.lastEvaluation(), state.verifiedResults(), remaining,
            remainingBudget);
        try {
            ReplanResult result = callReplanner(
                request, deadlineNanos, executionSession);
            mutationApplier.apply(state, result);
        } catch (Exception error) {
            state.failTask(
                failed.task().id(), "局部重规划失败：" + safeMessage(error));
        }
        return true;
    }

    private ReplanResult callReplanner(
        ReplanRequest request, long deadlineNanos,
        AgentExecutionSession executionSession
    ) throws Exception {
        long remainingNanos = deadlineNanos - System.nanoTime();
        long timeoutNanos = Math.min(
            remainingNanos, replanPolicy.timeout().toNanos());
        if (timeoutNanos <= 0) throw new TimeoutException("局部重规划超时");
        Future<ReplanResult> future = executor.submit(() -> replanner.replan(request));
        if (executionSession != null) executionSession.register(future);
        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new TimeoutException("局部重规划超过 "
                + replanPolicy.timeout().toSeconds() + " 秒");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new Exception("局部重规划执行失败", cause);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Exception("局部重规划被中断", error);
        } finally {
            if (executionSession != null) executionSession.unregister(future);
        }
    }

    private void failActionableTasks(AgentExecutionState state, String reason) {
        state.retryPendingTaskStates().forEach(item ->
            state.failTask(item.task().id(), reason));
        state.replanRequiredTaskStates().forEach(item ->
            state.failTask(item.task().id(), reason));
        state.failUnresolvedTasks(reason);
    }

    private AgentResponse executeFallback(
        String userText,
        String history,
        long deadlineNanos,
        AgentRequestContext requestContext
    ) throws Exception {
        AgentExecutionSession executionSession = executionControl == null
            ? null : executionControl.begin(requestContext, userText);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException(
                "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒");
        }
        Future<String> future = executor.submit(
            () -> requestContextHolder.callWith(
                requestContext,
                () -> fallbackChatService.chat(userText, history)));
        if (executionSession != null) executionSession.register(future);
        try {
            AgentResponse response = AgentResponse.text(
                future.get(remainingNanos, TimeUnit.NANOSECONDS));
            if (executionControl != null) {
                executionControl.finish(executionSession, AgentRunStatus.SUCCEEDED,
                    List.of("task-1"), List.of(), false, requestContext.userId());
            }
            return response;
        } catch (CancellationException error) {
            if (executionControl != null) {
                executionControl.finish(executionSession, AgentRunStatus.CANCELLED,
                    List.of(), List.of("task-1"), false, requestContext.userId());
            }
            return AgentResponse.text("任务 " + executionSession.executionId()
                + " 已取消。没有检测到已完成的副作用操作。");
        } catch (TimeoutException error) {
            future.cancel(true);
            finishFailed(executionSession, requestContext);
            throw new TimeoutException(
                "Agent 执行时间超过 " + executionTimeout.toSeconds() + " 秒");
        } catch (ExecutionException error) {
            finishFailed(executionSession, requestContext);
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new Exception("Agent 单任务执行失败", cause);
        } catch (InterruptedException error) {
            future.cancel(true);
            finishFailed(executionSession, requestContext);
            Thread.currentThread().interrupt();
            throw new Exception("Agent 执行被中断", error);
        }
    }

    private void finishFailed(
        AgentExecutionSession session, AgentRequestContext context
    ) {
        if (executionControl != null && session != null) {
            executionControl.finish(session, AgentRunStatus.FAILED,
                List.of(), List.of(), false, context.userId());
        }
    }

    private List<AgentTaskResult> executeReadyTasks(
        List<AgentTask> ready,
        Map<String, AgentTaskResult> completed,
        String supportingContext,
        String history,
        long deadlineNanos,
        AgentRequestContext requestContext,
        List<AgentInputAttachment> inputAttachments,
        Map<String, List<DataLineageRecord>> lineageByTask,
        AgentExecutionSession executionSession
    ) {
        List<Future<AgentTaskResult>> futures = new ArrayList<>();
        for (AgentTask task : ready) {
            Future<AgentTaskResult> future = executor.submit(
                () -> requestContextHolder.callWith(
                    requestContext,
                    () -> executeTask(
                        task,
                        completed,
                        supportingContext,
                        history,
                        inputAttachments,
                        lineageByTask.getOrDefault(task.id(), List.of()))));
            futures.add(future);
            if (executionSession != null) executionSession.register(future);
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
                if (isCancellationRequested(executionSession)) {
                    cancelRemaining(futures, index);
                    break;
                }
                results.add(futures.get(index).get(remainingNanos, TimeUnit.NANOSECONDS));
                if (executionSession != null) executionSession.unregister(futures.get(index));
            } catch (CancellationException error) {
                cancelRemaining(futures, index);
                break;
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

    private boolean isCancellationRequested(AgentExecutionSession session) {
        return session != null && session.token().isCancellationRequested();
    }

    private AgentResponse cancellationResponse(
        AgentExecutionSession session, AgentExecutionState state, boolean partial
    ) {
        StringBuilder text = new StringBuilder("任务 ")
            .append(session.executionId()).append(" 已")
            .append(partial ? "部分取消" : "取消").append("。\n")
            .append("已完成步骤：").append(state.completedTaskIds().size()).append(" 个；")
            .append("已停止或未执行步骤：").append(state.cancelledTaskIds().size()).append(" 个。");
        if (partial) {
            text.append("\n部分已完成步骤产生了实际变更，系统不会自动撤销；如需回滚请明确提出。");
        } else {
            text.append("\n没有检测到已完成的副作用操作。");
        }
        return AgentResponse.text(text.toString());
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
        List<AgentInputAttachment> inputAttachments,
        List<DataLineageRecord> lineage
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
                    inputAttachments,
                    task.input(),
                    lineage));
        } catch (Exception error) {
            return AgentTaskResult.failure(task, "处理失败：" + safeMessage(error));
        }
    }

    private AgentResponse aggregate(AgentExecutionState state) {
        List<AgentTaskResult> results = state.taskStates().stream()
            .sorted(Comparator.comparingInt(item -> item.task().order()))
            .map(this::resultForAggregation)
            .toList();
        List<AgentAttachment> attachments = results.stream()
            .flatMap(result -> result.attachments().stream())
            .toList();

        if (results.size() == 1) {
            AgentTaskResult result = results.get(0);
            if (result.hasMultipleTexts()) {
                return AgentResponse.multi(result.texts());
            }
            return new AgentResponse(
                result.succeeded() ? result.text() : result.error(),
                List.of(), attachments);
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
        return new AgentResponse(reply.toString(), List.of(), attachments);
    }

    private AgentTaskResult resultForAggregation(AgentTaskState state) {
        if (state.status() == TaskStatus.VERIFIED && state.lastResult() != null) {
            return state.lastResult();
        }
        if (state.status() == TaskStatus.FAILED && state.lastResult() != null) {
            return state.lastResult();
        }
        TaskEvaluation evaluation = state.lastEvaluation();
        if (evaluation == null) {
            return AgentTaskResult.failure(
                state.task(), "任务未完成，当前状态：" + state.status());
        }
        StringBuilder error = new StringBuilder("任务验收未通过 [")
            .append(evaluation.decision()).append('/')
            .append(evaluation.code()).append("]：")
            .append(evaluation.reason());
        if (!evaluation.failedCriteria().isEmpty()) {
            error.append("；失败条件：")
                .append(String.join("、", evaluation.failedCriteria()));
        }
        if (!evaluation.correctiveHint().isBlank()) {
            error.append("；建议：").append(evaluation.correctiveHint());
        }
        error.append("；当前状态：").append(state.status());
        return AgentTaskResult.failure(state.task(), error.toString());
    }

    private String compactLabel(String instruction) {
        String label = instruction.replaceAll("\\s+", " ").trim();
        return label.length() <= 36 ? label : label.substring(0, 36) + "…";
    }

    private AgentTask withInput(AgentTask task, com.fasterxml.jackson.databind.JsonNode input) {
        return new AgentTask(
            task.id(), task.order(), task.type(), task.skillName(), task.instruction(),
            input, task.expectedOutput(), task.acceptanceCriteria(), task.dependencies());
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
