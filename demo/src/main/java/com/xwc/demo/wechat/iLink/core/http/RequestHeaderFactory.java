package com.xwc.demo.wechat.iLink.core.http;

import com.xwc.demo.wechat.iLink.core.config.ILinkConfig;
import com.xwc.demo.wechat.iLink.core.login.LoginContext;
import com.xwc.demo.wechat.iLink.core.utils.RandomUtils;
import java.util.HashMap;
import java.util.Map;

public final class RequestHeaderFactory {
  private RequestHeaderFactory() {}

  public static Map<String, String> businessHeaders(
      ILinkConfig config, LoginContext loginContext, byte[] utf8Body) {
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("Content-Type", "application/json");//请求体内容类型
    headers.put("AuthorizationType", "ilink_bot_token");//授权类型
    headers.put("Authorization", "Bearer " + loginContext.getBotToken());
    headers.put("X-WECHAT-UIN", RandomUtils.randomWechatUin());//随机 UIN
    headers.put("Content-Length", String.valueOf(utf8Body == null ? 0 : utf8Body.length));//请求体长度
    if (config.getRouteTag() != null && !config.getRouteTag().trim().isEmpty())//路由标签
      headers.put("SKRouteTag", config.getRouteTag());//路由标签
    return headers;
  }
}
