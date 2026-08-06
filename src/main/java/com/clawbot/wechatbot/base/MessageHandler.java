package com.clawbot.wechatbot.base;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/**
 * 消息处理器接口 —— 策略模式
 *
 * 每种消息类型一个实现类：
 *   - TextMessageHandler 处理普通文本（DeepSeek 对话）
 *   - ImageMessageHandler 处理图片消息（百炼看图）
 *   - DocumentMessageHandler 处理用户发送的文件
 *
 * 框架层通过 canHandle() 判断由谁处理，然后调用 handle()
 */
public interface MessageHandler {

    /**
     * 判断能否处理这条消息
     */
    boolean canHandle(WeixinMessage msg);

    /**
     * 实际处理这条消息
     */
    void handle(ILinkClient client, WeixinMessage msg);

    /**
     * Handler 的优先级（数字小的先尝试）
     */
    default int priority() { return 100; }

    /**
     * 是否需要绕过统一任务规划直接处理（如表单截图等强领域意图）。
     * 返回 true 的处理器会在 LLM 规划之前优先尝试 canHandle，避免图片消息
     * 被规划层改写成无法解析的指令或劫持。
     */
    default boolean bypassesPlanning() { return false; }
}
