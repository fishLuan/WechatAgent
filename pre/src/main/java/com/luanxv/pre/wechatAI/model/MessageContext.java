package com.luanxv.pre.wechatAI.model;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class MessageContext {
    //原始消息对象
    //发送者用户ID
    private String fromUserId;
    //文本内容
    private String textContent;
    //消息项目列表
    //图片项目
    private MessageItem imageItem;
    private MessageItem voiceItem;
    private MessageItem fileItem;
    //是否包含图片
    private boolean hasImage;
    private boolean hasVoice;
    private boolean hasFile;
}
