package com.clawbot.wechatbot.scheduler;

import com.github.wechat.ilink.sdk.ILinkClient;

public record WeChatClientReadyEvent(String botId, ILinkClient client) {}