package com.clawbot.wechatbot.messaging;

import com.github.wechat.ilink.sdk.core.model.MessageItem;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个用户最近一张图片的临时缓存：微信里「图片」和「做成表」通常是分开发送的两条消息，
 * 短窗口内（如 30 秒）用缓存把图片与后续表格意图文字关联成同一次操作。
 */
public final class RecentImageCache {

    private record Entry(MessageItem imageItem, long receivedAtMs) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long windowMs;

    public RecentImageCache(long windowMs) {
        this.windowMs = windowMs;
    }

    /** 记录用户最近一张图片（后发覆盖先发）。 */
    public void remember(String userId, MessageItem imageItem) {
        if (userId == null || userId.isBlank() || imageItem == null) {
            return;
        }
        entries.put(userId, new Entry(imageItem, System.currentTimeMillis()));
    }

    /** 窗口内取出并消费该用户最近一张图片；过期或不存在返回 empty。 */
    public Optional<MessageItem> take(String userId, long nowMs) {
        Entry entry = entries.get(userId);
        if (entry == null || nowMs - entry.receivedAtMs() > windowMs) {
            return Optional.empty();
        }
        entries.remove(userId);
        return Optional.of(entry.imageItem());
    }
}
