package com.xwc.demo.wechat.iLink.core.exception;

public class ProtocolException extends ILinkException {
  public ProtocolException(String m) {
    super(m);
  }

  public ProtocolException(String m, Throwable c) {
    super(m, c);
  }
}
