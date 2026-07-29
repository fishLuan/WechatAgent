package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PendingRecommendationStoreTests {

    private PendingRecommendationStore store;
    private final String userId = "wechat-user-1";

    @BeforeEach
    void setUp() {
        store = new PendingRecommendationStore();
    }

    @Test
    void storesAndRetrievesRecommendations() {
        List<RecommendedContent> items = List.of(
            makeItem("content-1", "番剧A", 9.5),
            makeItem("content-2", "番剧B", 8.5));

        store.put(userId, ContentType.BANGUMI, items);

        assertEquals(items, store.getPendingItems(userId, ContentType.BANGUMI));
        assertTrue(store.hasRecentPending(userId, ContentType.BANGUMI));
    }

    @Test
    void findByItemNumberReturnsCorrectItem() {
        List<RecommendedContent> items = List.of(
            makeItem("content-1", "番剧A", 9.5),
            makeItem("content-2", "番剧B", 8.5),
            makeItem("content-3", "番剧C", 7.5));

        store.put(userId, ContentType.BANGUMI, items);

        // 1-based indexing
        assertEquals("content-1", store.findByItemNumber(userId, ContentType.BANGUMI, 1).contentId());
        assertEquals("content-2", store.findByItemNumber(userId, ContentType.BANGUMI, 2).contentId());
        assertEquals("content-3", store.findByItemNumber(userId, ContentType.BANGUMI, 3).contentId());
    }

    @Test
    void returnsNullForOutOfRangeItemNumber() {
        store.put(userId, ContentType.BANGUMI, List.of(makeItem("c1", "A", 9.0)));
        assertNull(store.findByItemNumber(userId, ContentType.BANGUMI, 2));
        assertNull(store.findByItemNumber(userId, ContentType.BANGUMI, 0));
    }

    @Test
    void returnsNullForNonExistentStore() {
        assertNull(store.findByItemNumber(userId, ContentType.BANGUMI, 1));
    }

    @Test
    void animeAndMoviePoolsDoNotMix() {
        store.put(userId, ContentType.BANGUMI, List.of(
            makeItem("anime-1", "番剧A", 9.0)));
        store.put(userId, ContentType.MOVIE, List.of(
            makeItem("movie-1", "电影B", 8.0)));

        assertEquals("anime-1",
            store.findByItemNumber(userId, ContentType.BANGUMI, 1).contentId());
        assertEquals("movie-1",
            store.findByItemNumber(userId, ContentType.MOVIE, 1).contentId());
    }

    @Test
    void removeClearsStoreForUserAndType() {
        store.put(userId, ContentType.BANGUMI, List.of(makeItem("c1", "A", 9.0)));
        store.remove(userId, ContentType.BANGUMI);

        assertFalse(store.hasRecentPending(userId, ContentType.BANGUMI));
        assertTrue(store.getPendingItems(userId, ContentType.BANGUMI).isEmpty());
    }

    @Test
    void differentUsersAreIsolated() {
        store.put("user-1", ContentType.BANGUMI, List.of(makeItem("c1", "A", 9.0)));
        store.put("user-2", ContentType.BANGUMI, List.of(makeItem("c2", "B", 8.0)));

        assertNotNull(store.findByItemNumber("user-1", ContentType.BANGUMI, 1));
        assertNotNull(store.findByItemNumber("user-2", ContentType.BANGUMI, 1));
        assertEquals("A", store.findByItemNumber("user-1", ContentType.BANGUMI, 1).title());
        assertEquals("B", store.findByItemNumber("user-2", ContentType.BANGUMI, 1).title());
    }

    @Test
    void tracksLastActiveType() {
        store.setLastActiveType(userId, ContentType.BANGUMI);
        assertEquals(ContentType.BANGUMI, store.getLastActiveType(userId));

        store.setLastActiveType(userId, ContentType.MOVIE);
        assertEquals(ContentType.MOVIE, store.getLastActiveType(userId));
    }

    private static RecommendedContent makeItem(String contentId, String title, double rating) {
        return new RecommendedContent(
            ContentType.BANGUMI,
            contentId,
            null,
            title,
            rating,
            Set.of(),
            null,
            null,
            "评分 " + rating);
    }
}
