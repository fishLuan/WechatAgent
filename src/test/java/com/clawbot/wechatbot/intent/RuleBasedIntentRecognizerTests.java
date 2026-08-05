package com.clawbot.wechatbot.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedIntentRecognizerTests {
    private final IntentRecognizer recognizer = new RuleBasedIntentRecognizer();

    @Test
    void recognizesSubscriptionByTitle() {
        IntentResult result =
            recognizer.recognize("我想订阅紫罗兰的永恒花园");

        assertEquals(IntentType.BILIBILI_SUBSCRIBE_TITLE, result.type());
        assertEquals("紫罗兰的永恒花园", result.slot("title"));
        assertTrue(result.confidence() >= 0.95);
    }

    @Test
    void recognizesSearchUrlAndIndexIntents() {
        assertEquals(
            IntentType.CONTENT_SEARCH_AMBIGUOUS,
            recognizer.recognize("搜索 老友记").type());
        assertEquals(
            IntentType.BILIBILI_SUBSCRIBE_URL,
            recognizer.recognize(
                "https://www.bilibili.com/bangumi/play/ss39444").type());
        assertEquals(
            IntentType.BILIBILI_SUBSCRIBE_INDEX,
            recognizer.recognize("订阅2").type());
        assertEquals(
            IntentType.BILIBILI_SUBSCRIBE_INDEX,
            recognizer.recognize("订阅第三个").type());
    }

    @Test
    void sendsCompoundRequestsToAgentFallback() {
        IntentResult result =
            recognizer.recognize("查询杭州天气，然后生成一张图片");

        assertEquals(IntentType.MULTI_TASK, result.type());
    }

    @Test
    void doesNotMisclassifyOrdinaryRemindersAsBilibili() {
        IntentResult result = recognizer.recognize("明天下午三点提醒我开会");

        assertFalse(result.isBilibiliIntent());
    }

    @Test
    void recognizesWatchedTitle() {
        IntentResult result =
            recognizer.recognize("我已经看过航海王：红发歌姬");

        assertEquals(IntentType.BILIBILI_MARK_TITLE, result.type());
        assertEquals("watched", result.slot("state"));
        assertEquals("航海王：红发歌姬", result.slot("title"));
    }

    @Test
    void recognizesPostfixWatchedTitle() {
        IntentResult result =
            recognizer.recognize("航海王：红发歌姬我已经看过了");

        assertEquals(IntentType.BILIBILI_MARK_TITLE, result.type());
        assertEquals("watched", result.slot("state"));
        assertEquals("航海王：红发歌姬", result.slot("title"));
    }

    @Test
    void doesNotTreatGenericViewingRequestAsBilibiliFeedback() {
        assertFalse(recognizer.recognize("我想看杭州天气").isBilibiliIntent());
        assertTrue(recognizer.recognize("我想看一些电影").isBilibiliIntent());
    }

    @Test
    void separatesBookAndVideoDomains() {
        assertEquals(IntentType.WEREAD_QUERY,
            recognizer.recognize("帮我搜一下三体这本书").type());
        assertEquals(IntentType.BILIBILI_SEARCH_TITLE,
            recognizer.recognize("搜百日成王动漫").type());
        assertEquals("百日成王",
            recognizer.recognize("搜百日成王动漫").slot("title"));
        assertEquals(IntentType.BILIBILI_RECOMMEND,
            recognizer.recognize("哈哈 想看动漫").type());
        assertEquals(IntentType.CONTENT_SEARCH_AMBIGUOUS,
            recognizer.recognize("搜三体").type());
    }
}
