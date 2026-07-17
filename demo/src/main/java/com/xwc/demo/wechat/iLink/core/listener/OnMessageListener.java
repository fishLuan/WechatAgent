package com.xwc.demo.wechat.iLink.core.listener;

import com.xwc.demo.wechat.iLink.core.model.WeixinMessage;
import java.util.List;

public interface OnMessageListener {
  void onMessages(List<WeixinMessage> messages);
}
