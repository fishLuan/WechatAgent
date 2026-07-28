package com.clawbot.spring;

import com.clawbot.wechatbot.WeChatBotApplication;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.agent.AgentOrchestrator;
import com.clawbot.wechatbot.service.agent.AgentTaskHandler;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
    classes = WeChatBotApplication.class,
    properties = {
        "wechat.bot.enabled=false",
        "clawbot.memory.enabled=false",
        "spring.main.web-application-type=none"
    }
)
class ApplicationTests {
    @Autowired
    private FunctionToolRegistry toolRegistry;

    @Autowired
    private List<MessageHandler> handlers;

    @Autowired
    private ChatService chatService;

    @Autowired
    private AgentOrchestrator agentOrchestrator;

    @Autowired
    private List<AgentTaskHandler> agentTaskHandlers;

    @Test
    void contextLoads() {
        assertEquals(10, toolRegistry.size());
        assertEquals(3, handlers.size());
        assertEquals(2, agentTaskHandlers.size());
        assertTrue(toolRegistry.definitions().findValuesAsText("name").contains("convert_currency"));
        assertTrue(toolRegistry.definitions().findValuesAsText("name")
            .contains("calculate_bazi_fortune"));
        assertTrue(toolRegistry.definitions().findValuesAsText("name")
            .contains("get_current_time"));
        assertTrue(toolRegistry.definitions().findValuesAsText("name")
            .contains("validate_id_card"));
        assertTrue(toolRegistry.definitions().findValuesAsText("name")
            .contains("scheduler_manage"));
        assertInstanceOf(DeepSeekChatService.class, chatService);
        assertTrue(agentOrchestrator.isConfigured() == chatService.isConfigured());
    }

}
