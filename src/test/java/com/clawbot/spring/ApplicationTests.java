package com.clawbot.spring;

import com.clawbot.wechatbot.WeChatBotApplication;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
    classes = WeChatBotApplication.class,
    properties = {
        "wechat.bot.enabled=false",
        "spring.main.web-application-type=none"
    }
)
class ApplicationTests {
    @Autowired
    private FunctionToolRegistry toolRegistry;

    @Autowired
    private List<MessageHandler> handlers;

    @Test
    void contextLoads() {
        assertEquals(6, toolRegistry.size());
        assertEquals(4, handlers.size());
        assertTrue(toolRegistry.definitions().findValuesAsText("name").contains("convert_currency"));
    }

}
