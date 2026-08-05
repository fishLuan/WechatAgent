package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.service.agent.AgentOrchestrator;
import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentResponse;
import com.clawbot.wechatbot.service.agent.state.TaskStatus;
import com.clawbot.wechatbot.confirmation.ConfirmationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

/** Recovers durable Agent executions after the Spring application is ready. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class AgentCheckpointRecoveryRunner implements ApplicationRunner {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final AgentCheckpointStore store;
    private final AgentOrchestrator orchestrator;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName();
    private ConfirmationService confirmations;

    public AgentCheckpointRecoveryRunner(
        AgentCheckpointStore store, AgentOrchestrator orchestrator
    ) {
        this.store = store;
        this.orchestrator = orchestrator;
    }

    @Autowired
    void configureConfirmations(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (AgentExecutionSnapshot snapshot : store.findRecoverableExecutions()) {
                recover(snapshot);
            }
        } catch (Exception error) {
            System.err.println("[AGENT-RECOVERY] 启动扫描失败，跳过本次恢复："
                + safeMessage(error));
        }
    }

    void recover(AgentExecutionSnapshot snapshot) {
        AgentExecutionCheckpoint execution = snapshot.execution();
        if (execution.getStatus() == AgentCheckpointExecutionStatus.WAITING_CONFIRMATION) {
            return;
        }
        if (!store.tryAcquireRecoveryLease(
            execution.getId(), owner, LEASE_DURATION)) return;
        try {
            AgentExecutionSnapshot claimed = store.load(execution.getId())
                .orElseThrow(() -> new IllegalStateException(
                    "获取租约后执行检查点不存在"));
            execution = claimed.execution();
            if (hasUncertainSideEffect(claimed)) {
                execution.setStatus(
                    AgentCheckpointExecutionStatus.WAITING_CONFIRMATION);
                execution.setFailureCode("RECOVERY_SIDE_EFFECT_UNCERTAIN");
                execution.setFailureMessage(
                    "程序重启时副作用任务处于执行中，需用户确认后才能继续，避免重复执行");
                store.saveExecution(execution);
                createRecoveryConfirmation(execution);
                System.err.println("[AGENT-RECOVERY] executionId="
                    + execution.getId() + " 等待确认：副作用执行结果不确定");
                return;
            }
            System.out.println("[AGENT-RECOVERY] 开始恢复 executionId="
                + execution.getId());
            AgentResponse response = orchestrator.resume(claimed);
            persistRecoveryResult(execution.getId(), response, false);
            System.out.println("[AGENT-RECOVERY] 恢复完成 executionId="
                + execution.getId());
        } catch (Exception error) {
            execution.setStatus(AgentCheckpointExecutionStatus.RETRY_WAITING);
            execution.setFailureCode("RECOVERY_FAILED");
            execution.setFailureMessage(safeMessage(error));
            try {
                store.saveExecution(execution);
            } catch (Exception persistenceError) {
                System.err.println("[AGENT-RECOVERY] 无法保存恢复失败状态 executionId="
                    + execution.getId() + "：" + safeMessage(persistenceError));
            }
            System.err.println("[AGENT-RECOVERY] 恢复失败 executionId="
                + execution.getId() + "：" + safeMessage(error));
        } finally {
            try {
                store.releaseRecoveryLease(execution.getId(), owner);
            } catch (Exception error) {
                System.err.println("[AGENT-RECOVERY] 释放恢复租约失败 executionId="
                    + execution.getId() + "：" + safeMessage(error));
            }
        }
    }

    public AgentResponse resumeConfirmed(String executionId) throws Exception {
        AgentExecutionSnapshot snapshot = store.load(executionId)
            .orElseThrow(() -> new IllegalArgumentException("找不到待恢复任务"));
        if (snapshot.execution().getStatus()
            != AgentCheckpointExecutionStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("该任务当前不需要恢复确认");
        }
        if (!store.tryAcquireRecoveryLease(executionId, owner, LEASE_DURATION)) {
            throw new IllegalStateException("任务正在由另一个实例恢复，请稍后查看结果");
        }
        try {
            AgentExecutionSnapshot claimed = store.load(executionId)
                .orElseThrow(() -> new IllegalStateException("恢复任务已不存在"));
            AgentResponse response = orchestrator.resume(claimed);
            persistRecoveryResult(executionId, response, true);
            return response;
        } finally {
            store.releaseRecoveryLease(executionId, owner);
        }
    }

    public void cancelRecovery(String executionId) {
        AgentExecutionSnapshot snapshot = store.load(executionId)
            .orElseThrow(() -> new IllegalArgumentException("找不到待恢复任务"));
        for (AgentTaskCheckpoint task : snapshot.tasks()) {
            if (!task.getStatus().terminal()) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setErrorCode("RECOVERY_CANCELLED");
                task.setErrorMessage("用户取消了重启恢复");
                task.setCompletedAt(Instant.now());
                store.saveTask(task);
            }
        }
        AgentExecutionCheckpoint execution = snapshot.execution();
        execution.setStatus(AgentCheckpointExecutionStatus.CANCELLED);
        execution.setFailureCode("RECOVERY_CANCELLED");
        execution.setFailureMessage("用户取消了重启恢复");
        store.saveExecution(execution);
    }

    private void createRecoveryConfirmation(AgentExecutionCheckpoint execution) {
        if (confirmations == null || execution.getUserId() == null
            || execution.getUserId().isBlank()) return;
        try {
            confirmations.createRecovery(new AgentRequestContext(
                execution.getUserId(), execution.getSourceMessageId()),
                execution.getId(),
                "重新执行程序重启时状态不确定的任务。该操作可能重复产生之前的副作用");
        } catch (Exception error) {
            System.err.println("[AGENT-RECOVERY] 创建恢复确认失败 executionId="
                + execution.getId() + "：" + safeMessage(error));
        }
    }

    private void persistRecoveryResult(
        String executionId, AgentResponse response, boolean delivered
    ) {
        store.load(executionId).ifPresent(snapshot -> {
            AgentExecutionCheckpoint execution = snapshot.execution();
            execution.setRecoveryResultText(response == null ? "" : response.text());
            execution.setRecoveryCompletedAt(Instant.now());
            execution.setRecoveryResultDelivered(delivered);
            store.saveExecution(execution);
        });
    }

    private boolean hasUncertainSideEffect(AgentExecutionSnapshot snapshot) {
        return snapshot.tasks().stream().anyMatch(task -> task.isSideEffect()
            && (task.getStatus() == TaskStatus.RUNNING
                || task.getStatus() == TaskStatus.VERIFYING));
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName() : message;
    }
}
