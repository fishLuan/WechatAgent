package com.luanxv.pre.wechatAI.handler;


import com.luanxv.pre.wechatAI.model.MessageContext;

public interface MessageTypeHandler {
    /**
     * 处理消息
     * @return 回复内容或 null（表示无需回复）
     */
    String handle(MessageContext context);

    /**
     * 是否支持处理该消息
     */
    boolean supports(MessageContext context);
}
