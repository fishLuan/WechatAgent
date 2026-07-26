package com.clawbot.wechatbot.service.multitask;

import com.clawbot.wechatbot.service.ChatService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Plans one message, executes independent tasks concurrently and aggregates
 * results in the user's original order.
 */
public final class MultiTaskChatService implements ChatService, AutoCloseable {
    private static final String NEWS_INPUT_MARKER = "\n用户问题：";

    private final ChatService singleTaskService;
    private final TaskPlanner planner;
    private final boolean enabled;
    private final ExecutorService executor;

    public MultiTaskChatService(ChatService singleTaskService, TaskPlanner planner,
                                boolean enabled, int maxParallelism) {
        this.singleTaskService = singleTaskService;
        this.planner = planner;
        this.enabled = enabled;
        this.executor = Executors.newFixedThreadPool(
            Math.max(1, maxParallelism),
            runnable -> {
                Thread thread = new Thread(runnable, "multi-task-chat");
                thread.setDaemon(true);
                return thread;
            });
    }

    @Override
    public String chat(String userText, String history) throws Exception {
        if (!enabled || userText == null || userText.isBlank()) {
            return singleTaskService.chat(userText, history);
        }

        PlanningInput input = splitSupportingContext(userText);
        List<String> tasks;
        try {
            tasks = planner.plan(input.userQuestion());
        } catch (Exception planningFailure) {
            System.err.println("[WARN] 多任务拆解失败，回退单任务流程: "
                + planningFailure.getMessage());
            return singleTaskService.chat(userText, history);
        }
        if (tasks.size() <= 1) return singleTaskService.chat(userText, history);

        List<CompletableFuture<TaskResult>> futures = new ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            int taskIndex = index;
            String task = tasks.get(index);
            futures.add(CompletableFuture.supplyAsync(
                () -> executeTask(taskIndex, task, input.supportingContext(), history),
                executor));
        }

        List<TaskResult> results = new ArrayList<>();
        for (CompletableFuture<TaskResult> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                results.add(new TaskResult(results.size(), "", null,
                    "处理失败：" + safeMessage(cause)));
            }
        }
        results.sort(java.util.Comparator.comparingInt(TaskResult::index));
        return aggregate(results);
    }

    private TaskResult executeTask(int index, String task, String supportingContext,
                                   String history) {
        String taskInput = "请只处理下面这一项用户需求，直接给出完整答案，不要遗漏其约束，"
            + "也不要提及任务拆解过程：\n" + task;
        if (!supportingContext.isBlank()) {
            taskInput = supportingContext + NEWS_INPUT_MARKER + taskInput;
        }
        try {
            return new TaskResult(index, task, singleTaskService.chat(taskInput, history), null);
        } catch (Exception e) {
            return new TaskResult(index, task, null, "处理失败：" + safeMessage(e));
        }
    }

    private String aggregate(List<TaskResult> results) {
        StringBuilder reply = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            TaskResult result = results.get(index);
            if (index > 0) reply.append("\n\n");
            reply.append(index + 1).append(". ");
            String label = result.task().replaceAll("\\s+", " ").trim();
            if (label.length() > 36) label = label.substring(0, 36) + "…";
            if (!label.isEmpty()) reply.append("【").append(label).append("】\n");
            reply.append(result.error() == null ? result.answer() : result.error());
        }
        return reply.toString();
    }

    private PlanningInput splitSupportingContext(String input) {
        int markerIndex = input.lastIndexOf(NEWS_INPUT_MARKER);
        if (markerIndex < 0) return new PlanningInput("", input);
        String context = input.substring(0, markerIndex);
        String question = input.substring(markerIndex + NEWS_INPUT_MARKER.length()).trim();
        if (question.isEmpty()) return new PlanningInput("", input);
        return new PlanningInput(context, question);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }

    @Override
    public boolean isConfigured() {
        return singleTaskService.isConfigured();
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

    private record TaskResult(int index, String task, String answer, String error) {
    }
}
