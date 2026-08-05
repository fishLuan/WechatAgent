package com.clawbot.wechatbot.base;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/** 能够确定性识别并执行、无需先调用 LLM 任务规划器的消息处理器。 */
public interface PlanningBypassMessageHandler extends MessageHandler {
    boolean canBypassPlanning(WeixinMessage message);
}
