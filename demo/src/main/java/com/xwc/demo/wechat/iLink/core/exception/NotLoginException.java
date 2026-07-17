package com.xwc.demo.wechat.iLink.core.exception;

public class NotLoginException extends ILinkException {
  public NotLoginException(String m) {
    super(m);
  }

  public NotLoginException(String m, Throwable c) {
    super(m, c);
  }
}
