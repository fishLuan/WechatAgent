package com.clawbot.wechatbot.base;

import com.clawbot.wechatbot.service.agent.AgentTask;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 可以直接消费入口层预规划任务的消息处理器。
 *
 * <p>用于保证同一条消息只规划一次，同时让多任务在领域处理器抢占前
 * 进入 Agent 外循环。</p>
 */
public interface PlannedMessageHandler extends MessageHandler {
    void handlePlanned(
        ILinkClient client,
        WeixinMessage message,
        List<AgentTask> tasks);
}
