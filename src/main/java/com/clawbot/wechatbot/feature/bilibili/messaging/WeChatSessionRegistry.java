package com.clawbot.wechatbot.feature.bilibili.messaging;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WeChatSessionRegistry {
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();

    public void markActive(String wechatUserId) {
        if (wechatUserId != null && !wechatUserId.isBlank()) {
            activeUsers.add(wechatUserId.trim());
        }
    }

    public boolean isActive(String wechatUserId) {
        return wechatUserId != null && !wechatUserId.isBlank()
            && activeUsers.contains(wechatUserId.trim());
    }

    public List<String> getAllActiveUsers() {
        return new ArrayList<>(activeUsers);
    }
}