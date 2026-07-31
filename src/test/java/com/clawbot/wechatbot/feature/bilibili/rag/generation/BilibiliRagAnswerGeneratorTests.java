package com.clawbot.wechatbot.feature.bilibili.rag.generation;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagContext;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagRequest;
import com.clawbot.wechatbot.service.ChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BilibiliRagAnswerGeneratorTests {

    @Test
    void fallbackForMovieDoesNotSuggestEpisodeSubscription() {
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(unconfiguredChatService());
        BilibiliRagAnswerGenerator generator =
            new BilibiliRagAnswerGenerator(provider);
        BilibiliRagContext context = new BilibiliRagContext(
            new BilibiliRagRequest(
                "user-1", "智能推荐电影", ContentType.MOVIE, null),
            List.of(new BilibiliRagDocument(
                ContentType.MOVIE,
                "movie-1",
                null,
                "测试电影",
                "一部高分电影",
                Set.of("剧情"),
                9.1,
                10000L,
                "https://www.bilibili.com/bangumi/play/ss1",
                null,
                null,
                true)),
            "不喜欢：恐怖电影");

        String answer = generator.generate(context);

        assertTrue(answer.contains("想看"));
        assertTrue(answer.contains("看过"));
        assertTrue(answer.contains("不喜欢"));
        assertFalse(answer.contains("追更"));
    }

    @Test
    void fallbackLimitsRecommendationsToThreeItems() {
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(unconfiguredChatService());
        BilibiliRagAnswerGenerator generator =
            new BilibiliRagAnswerGenerator(provider);
        BilibiliRagContext context = new BilibiliRagContext(
            new BilibiliRagRequest(
                "user-1", "智能推荐动漫", ContentType.BANGUMI, null),
            List.of(
                doc("作品1"),
                doc("作品2"),
                doc("作品3"),
                doc("作品4")),
            "");

        String answer = generator.generate(context);

        assertTrue(answer.contains("作品1"));
        assertTrue(answer.contains("作品3"));
        assertFalse(answer.contains("作品4"));
        assertEquals(3, answer.lines().filter(line -> line.matches("\\d+\\. .*")).count());
    }

    @Test
    void fallbackForFinishedBangumiDoesNotSuggestSubscription() {
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(unconfiguredChatService());
        BilibiliRagAnswerGenerator generator =
            new BilibiliRagAnswerGenerator(provider);
        BilibiliRagContext context = new BilibiliRagContext(
            new BilibiliRagRequest(
                "user-1", "智能推荐动漫", ContentType.BANGUMI, null),
            List.of(finishedDoc("夏目友人帐 第七季")),
            "");

        String answer = generator.generate(context);

        assertTrue(answer.contains("看过1"));
        assertFalse(answer.contains("订阅1"));
        assertFalse(answer.contains("追更"));
    }

    private ChatService unconfiguredChatService() {
        return new ChatService() {
            @Override
            public String chat(String userText, String history) {
                throw new AssertionError("未配置时不应调用模型");
            }

            @Override
            public boolean isConfigured() {
                return false;
            }
        };
    }

    private BilibiliRagDocument doc(String title) {
        return new BilibiliRagDocument(
            ContentType.BANGUMI,
            title,
            "season-" + title,
            title,
            "简介",
            Set.of("治愈"),
            9.0,
            1000L,
            "https://www.bilibili.com/bangumi/play/ss" + title,
            "全12话",
            12,
            false);
    }

    private BilibiliRagDocument finishedDoc(String title) {
        return new BilibiliRagDocument(
            ContentType.BANGUMI,
            title,
            "season-" + title,
            title,
            "简介",
            Set.of("治愈"),
            9.0,
            1000L,
            "https://www.bilibili.com/bangumi/play/ss" + title,
            "全13话",
            13,
            true);
    }
}
