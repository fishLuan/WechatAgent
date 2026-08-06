package com.clawbot.spring;

import com.clawbot.wechatbot.WeChatBotApplication;
import com.clawbot.wechatbot.feature.bilibili.rag.indexing.BilibiliRagIndexScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
    classes = WeChatBotApplication.class,
    properties = {
        "wechat.bot.enabled=false",
        "clawbot.memory.enabled=false",
        "clawbot.bilibili.enabled=true",
        "clawbot.bilibili.rag.vector.enabled=true",
        "spring.main.web-application-type=none"
    }
)
class BilibiliRagEnabledApplicationTests {
    @Autowired
    private BilibiliRagIndexScheduler scheduler;

    @Test
    void contextStartsWhenBilibiliRagSchedulingIsEnabled() {
        assertNotNull(scheduler);
    }
}
