package com.clawbot.wechatbot.messaging;

import com.github.wechat.ilink.sdk.ILinkClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 保存已登录的微信客户端，并记录每位用户最近通过哪个客户端发来消息。
 */
@Component
public class WeChatClientRegistry {
    private final Set<ILinkClient> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, ILinkClient> userClients = new ConcurrentHashMap<>();

    public void registerClient(ILinkClient client) {
        if (client != null) clients.add(client);
    }

    public void unregisterClient(ILinkClient client) {
        if (client == null) return;
        clients.remove(client);
        userClients.entrySet().removeIf(entry -> entry.getValue() == client);
    }

    public void bindUser(String userId, ILinkClient client) {
        if (userId == null || userId.isBlank() || client == null) return;
        clients.add(client);
        userClients.put(userId.trim(), client);
    }

    public ILinkClient requireClient(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("微信用户 ID 不能为空");
        }

        String normalizedUserId = userId.trim();
        ILinkClient mapped = userClients.get(normalizedUserId);
        if (isLoggedIn(mapped)) return mapped;
        if (mapped != null) userClients.remove(normalizedUserId, mapped);

        List<ILinkClient> loggedInClients = clients.stream()
            .filter(this::isLoggedIn)
            .toList();
        if (loggedInClients.size() == 1) {
            ILinkClient onlyClient = loggedInClients.get(0);
            userClients.put(normalizedUserId, onlyClient);
            return onlyClient;
        }
        if (loggedInClients.isEmpty()) {
            throw new IllegalStateException("当前没有已登录的微信会话");
        }
        throw new IllegalStateException(
            "存在多个微信会话，但用户 " + normalizedUserId + " 尚未绑定接收会话");
    }

    public boolean isReady() {
        return clients.stream().anyMatch(this::isLoggedIn);
    }

    public void clear() {
        userClients.clear();
        clients.clear();
    }

    private boolean isLoggedIn(ILinkClient client) {
        return client != null && client.isLoggedIn();
    }
}
