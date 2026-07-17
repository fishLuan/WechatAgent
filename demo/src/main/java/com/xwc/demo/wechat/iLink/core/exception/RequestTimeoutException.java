package com.xwc.demo.wechat.iLink.core.exception;

public class RequestTimeoutException extends ILinkException {
  public RequestTimeoutException(String m) {
    super(m);
  }

  public RequestTimeoutException(String m, Throwable c) {
    super(m, c);
  }
}
