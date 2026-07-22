package com.clawbot.wechatbot.base;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/**
 * 消息处理器接口 —— 策略模式
 *
 * 每种消息类型一个实现类：
 *   - TextMessageHandler 处理普通文本（DeepSeek 对话）
 *   - ImageMessageHandler 处理图片消息（百炼看图）
 *   - ImageGenHandler 处理"画图"指令（百炼文生图）
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
}