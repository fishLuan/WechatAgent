package com.clawbot.wechatbot.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleBasedIntentRecognizerDomainStatementTests {
    private final RuleBasedIntentRecognizer recognizer =
        new RuleBasedIntentRecognizer();

    @Test
    void bookPreferenceIsGeneralChat() {
        assertEquals(IntentType.GENERAL_CHAT,
            recognizer.recognize("我平时喜欢读科幻小说").type());
        assertEquals(IntentType.GENERAL_CHAT,
            recognizer.recognize("鲁迅是我喜欢的作者").type());
    }

    @Test
    void explicitBookOperationsRemainWereadQueries() {
        assertEquals(IntentType.WEREAD_QUERY,
            recognizer.recognize("推荐三本科幻小说").type());
        assertEquals(IntentType.WEREAD_QUERY,
            recognizer.recognize("在微信读书搜索三体").type());
        assertEquals(IntentType.WEREAD_QUERY,
            recognizer.recognize("查看我的书架").type());
    }

    @Test
    void mediaPreferenceIsGeneralButRecommendationIsBilibili() {
        assertEquals(IntentType.GENERAL_CHAT,
            recognizer.recognize("我在上海，我喜欢看动漫").type());
        assertEquals(IntentType.BILIBILI_RECOMMEND,
            recognizer.recognize("给我推荐三部动漫").type());
    }

    @Test
    void subscriptionListQuestionIsNotA_TitleSubscription() {
        assertEquals(IntentType.GENERAL_CHAT,
            recognizer.recognize("我订阅了哪些作品").type());
    }
}
