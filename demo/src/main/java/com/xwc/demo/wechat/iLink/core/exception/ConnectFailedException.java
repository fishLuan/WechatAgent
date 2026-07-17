package com.xwc.demo.wechat.iLink.core.exception;

public class ConnectFailedException extends ILinkException {
  public ConnectFailedException(String m) {
    super(m);
  }

  public ConnectFailedException(String m, Throwable c) {
    super(m, c);
  }
}
