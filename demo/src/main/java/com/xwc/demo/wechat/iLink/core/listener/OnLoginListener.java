package com.xwc.demo.wechat.iLink.core.listener;

import com.xwc.demo.wechat.iLink.core.login.LoginContext;

public interface OnLoginListener {
  void onLoginSuccess(LoginContext context);

  void onLoginFailure(Throwable throwable);
}
