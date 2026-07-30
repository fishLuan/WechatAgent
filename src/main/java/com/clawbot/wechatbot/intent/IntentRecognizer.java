package com.clawbot.wechatbot.intent;

public interface IntentRecognizer {
    IntentResult recognize(String userText);
}
