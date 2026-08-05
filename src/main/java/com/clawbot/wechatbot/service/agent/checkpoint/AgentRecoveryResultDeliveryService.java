package com.clawbot.wechatbot.service.agent.checkpoint;

import com.clawbot.wechatbot.base.MessageSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Delivers results produced before the WeChat session became available again. */
@Service
public final class AgentRecoveryResultDeliveryService {
    private final AgentCheckpointStore store;
    private final MessageSender sender;

    public AgentRecoveryResultDeliveryService(
        AgentCheckpointStore store, MessageSender sender
    ) {
        this.store = store;
        this.sender = sender;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void deliverPending() {
        try {
            for (AgentExecutionCheckpoint execution
                : store.findUnnotifiedRecoveryConfirmations()) {
                deliverConfirmation(execution);
            }
            for (AgentExecutionCheckpoint execution
                : store.findUndeliveredRecoveryResults()) {
                deliver(execution);
            }
        } catch (Exception error) {
            System.err.println("[AGENT-RECOVERY] 查询待投递恢复结果失败："
                + safeMessage(error));
        }
    }

    void deliverConfirmation(AgentExecutionCheckpoint execution) {
        String userId = execution.getUserId();
        if (userId == null || userId.isBlank() || !sender.isReadyFor(userId)) return;
        try {
            sender.sendText(userId,
                "检测到一个因程序重启而中断的任务，其中有一步的执行结果无法确定。"
                    + "为避免重复操作，系统没有自动重试。\n\n"
                    + "回复“确认”重新执行未确定的步骤；回复“取消”终止这个任务。");
            execution.setRecoveryConfirmationNotified(true);
            store.saveExecution(execution);
        } catch (Exception error) {
            System.err.println("[AGENT-RECOVERY] 恢复确认提示投递失败 executionId="
                + execution.getId() + "：" + safeMessage(error));
        }
    }

    void deliver(AgentExecutionCheckpoint execution) {
        String userId = execution.getUserId();
        if (userId == null || userId.isBlank() || !sender.isReadyFor(userId)) return;
        try {
            String result = execution.getRecoveryResultText();
            sender.sendText(userId, "之前中断的任务已经恢复完成。"
                + (result == null || result.isBlank() ? "" : "\n\n" + result));
            execution.setRecoveryResultDelivered(true);
            store.saveExecution(execution);
        } catch (Exception error) {
            System.err.println("[AGENT-RECOVERY] 恢复结果投递失败 executionId="
                + execution.getId() + "：" + safeMessage(error));
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName() : message;
    }
}
