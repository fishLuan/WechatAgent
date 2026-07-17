package com.xwc.demo.wechat.iLink.core.retry;

public interface BackoffStrategy {
  long nextDelayMillis(int attempt);
}
