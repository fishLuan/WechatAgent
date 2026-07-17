package com.xwc.demo.wechat.iLink.service;

import com.xwc.demo.wechat.iLink.core.config.ILinkConfig;
import com.xwc.demo.wechat.iLink.core.context.ContextPoolManager;
import com.xwc.demo.wechat.iLink.core.context.ConversationContext;
import com.xwc.demo.wechat.iLink.core.http.BusinessApiClient;
import com.xwc.demo.wechat.iLink.core.login.LoginContext;
import com.xwc.demo.wechat.iLink.core.model.*;

import java.io.IOException;

public class TypingService {
  private final ILinkConfig config;
  private final BusinessApiClient apiClient;
  private final ContextPoolManager contextPoolManager;

  public TypingService(
      ILinkConfig config, BusinessApiClient apiClient, ContextPoolManager contextPoolManager) {
    this.config = config;
    this.apiClient = apiClient;
    this.contextPoolManager = contextPoolManager;
  }

  public String ensureTypingTicket(LoginContext loginContext, String userId) throws IOException {
    ConversationContext ctx = contextPoolManager.getOrCreate(loginContext.getBotId(), userId);
    if (ctx.getTypingTicket() != null) return ctx.getTypingTicket();
    GetConfigResponse resp =
        apiClient.post(
            loginContext,
            "/ilink/bot/getconfig",
            new GetConfigRequest(
                userId, ctx.getLatestContextToken(), new BaseInfo(config.getChannelVersion())),
            GetConfigResponse.class);
    ctx.setTypingTicket(resp.getTyping_ticket());
    return resp.getTyping_ticket();
  }

  public void startTyping(LoginContext loginContext, String userId) throws IOException {
    apiClient.post(
        loginContext,
        "/ilink/bot/sendtyping",
        new SendTypingRequest(
            userId,
            ensureTypingTicket(loginContext, userId),
            1,
            new BaseInfo(config.getChannelVersion())),
        ApiResponse.class);
  }

  public void stopTyping(LoginContext loginContext, String userId) throws IOException {
    ConversationContext ctx = contextPoolManager.getOrCreate(loginContext.getBotId(), userId);
    if (ctx.getTypingTicket() == null) return;
    apiClient.post(
        loginContext,
        "/ilink/bot/sendtyping",
        new SendTypingRequest(
            userId, ctx.getTypingTicket(), 2, new BaseInfo(config.getChannelVersion())),
        ApiResponse.class);
  }
}