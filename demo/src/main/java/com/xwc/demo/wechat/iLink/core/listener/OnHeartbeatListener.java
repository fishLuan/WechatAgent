package com.xwc.demo.wechat.iLink.core.listener;

public interface OnHeartbeatListener {
  void onHeartbeatSuccess();

  void onHeartbeatFailure(Throwable cause);
}
