package com.clawbot.wechatbot.messaging;

import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 最近图片缓存测试：窗口内可取、过期不可取、取后消费、按用户隔离。 */
class RecentImageCacheTests {

    private final RecentImageCache cache = new RecentImageCache(30_000L);

    private MessageItem imageItem() {
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        return item;
    }

    @Test
    void takeReturnsRememberedImageWithinWindow() {
        cache.remember("user-1", imageItem());
        Optional<MessageItem> taken = cache.take("user-1", System.currentTimeMillis());
        assertTrue(taken.isPresent());
    }

    @Test
    void expiredImageIsNotTaken() {
        cache.remember("user-1", imageItem());
        assertTrue(cache.take("user-1", System.currentTimeMillis() + 31_000L).isEmpty());
    }

    @Test
    void takeConsumesEntryOnce() {
        cache.remember("user-1", imageItem());
        assertTrue(cache.take("user-1", System.currentTimeMillis()).isPresent());
        assertTrue(cache.take("user-1", System.currentTimeMillis()).isEmpty());
    }

    @Test
    void entriesAreIsolatedPerUser() {
        cache.remember("user-1", imageItem());
        assertTrue(cache.take("user-2", System.currentTimeMillis()).isEmpty());
        assertTrue(cache.take("user-1", System.currentTimeMillis()).isPresent());
    }

    @Test
    void blankUserIsIgnored() {
        cache.remember("", imageItem());
        assertFalse(cache.take("", System.currentTimeMillis()).isPresent());
    }
}
