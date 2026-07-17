package com.xwc.demo.wechat.iLink.core.serializer;

public interface Serializer {
  String serialize(Object obj);

  <T> T deserialize(String text, Class<T> clazz);
}
