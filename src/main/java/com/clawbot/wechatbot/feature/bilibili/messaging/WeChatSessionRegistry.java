package com.clawbot.wechatbot.feature.bilibili.messaging;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记录近期与机器人交互过的微信用户。
 *
 * <p>真正的 SDK 会话归公共 WeChatClientRegistry 管理；这里仅保存业务层活跃状态，
 * 防止后台任务向从未建立过会话的用户盲目发送。</p>
 */
@Component
public final class WeChatSessionRegistry {
    private static final Duration ACTIVE_WINDOW = Duration.ofDays(30);
    private final ConcurrentMap<String, Instant> activeUsers = new ConcurrentHashMap<>();

    public void markActive(String wechatUserId) {
        if (wechatUserId != null && !wechatUserId.isBlank()) {
            activeUsers.put(wechatUserId.trim(), Instant.now());
        }
    }

    public boolean isActive(String wechatUserId) {
        if (wechatUserId == null || wechatUserId.isBlank()) return false;
        Instant lastSeen = activeUsers.get(wechatUserId.trim());
        return lastSeen != null && lastSeen.plus(ACTIVE_WINDOW).isAfter(Instant.now());
    }

    public void remove(String wechatUserId) {
        if (wechatUserId != null) activeUsers.remove(wechatUserId.trim());
    }

    public void clear() {
        activeUsers.clear();
    }
}
