package com.xwc.demo.wechat.iLink.service;

import com.xwc.demo.wechat.iLink.core.config.ILinkConfig;
import com.xwc.demo.wechat.iLink.core.context.ContextPoolManager;
import com.xwc.demo.wechat.iLink.core.context.GetUpdatesCursorStore;
import com.xwc.demo.wechat.iLink.core.http.BusinessApiClient;
import com.xwc.demo.wechat.iLink.core.login.LoginContext;
import com.xwc.demo.wechat.iLink.core.model.BaseInfo;
import com.xwc.demo.wechat.iLink.core.model.GetUpdatesRequest;
import com.xwc.demo.wechat.iLink.core.model.GetUpdatesResponse;
import com.xwc.demo.wechat.iLink.core.model.WeixinMessage;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class UpdateService {
  private final ILinkConfig config;
  private final BusinessApiClient apiClient;
  private final GetUpdatesCursorStore cursorStore;
  private final ContextPoolManager contextPoolManager;

  public UpdateService(
      ILinkConfig config,
      BusinessApiClient apiClient,
      GetUpdatesCursorStore cursorStore,
      ContextPoolManager contextPoolManager) {
    this.config = config;
    this.apiClient = apiClient;
    this.cursorStore = cursorStore;
    this.contextPoolManager = contextPoolManager;
  }
//接受微信消息
  public List<WeixinMessage> poll(LoginContext loginContext) throws IOException {
    String cursor = cursorStore.get();
    if (cursor == null) cursor = "";
    GetUpdatesResponse resp =
        apiClient.post(
            loginContext,
            "/ilink/bot/getupdates",
            new GetUpdatesRequest(cursor, new BaseInfo(config.getChannelVersion())),
            GetUpdatesResponse.class);
    if (resp.getGet_updates_buf() != null)
      cursorStore.put(resp.getGet_updates_buf());
    List<WeixinMessage> msgs = resp.getMsgs();
    if (msgs == null) return Collections.<WeixinMessage>emptyList();
    for (WeixinMessage msg : msgs) {
      if (msg.getFrom_user_id() != null
          && msg.getContext_token() != null
          && !msg.getContext_token().trim().isEmpty()) {
        contextPoolManager
            .getOrCreate(loginContext.getBotId(), msg.getFrom_user_id())
            .updateContextToken(
                msg.getContext_token(),// 存下对方发来的 token
                    msg.getMessage_id(), //消息 ID
                    msg.getCreate_time_ms());//消息创建时间
      }
    }
    return msgs;
  }
}