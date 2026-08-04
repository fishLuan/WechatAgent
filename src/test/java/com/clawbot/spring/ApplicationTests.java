package com.clawbot.spring;

import com.clawbot.wechatbot.WeChatBotApplication;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliPreferenceRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliUpdateEventRepository;
import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandMessageHandler;
import com.clawbot.wechatbot.feature.excel.messaging.ExcelFileMessageHandler;
import com.clawbot.wechatbot.feature.excel.messaging.ExcelScreenshotMessageHandler;
import com.clawbot.wechatbot.handler.DocumentMessageHandler;
import com.clawbot.wechatbot.handler.ImageMessageHandler;
import com.clawbot.wechatbot.handler.TextMessageHandler;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.agent.AgentOrchestrator;
import com.clawbot.wechatbot.service.agent.AgentTaskHandler;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.skills.SkillManager;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
    classes = WeChatBotApplication.class,
    properties = {
        "wechat.bot.enabled=false",
        "clawbot.memory.enabled=false",
        "clawbot.bilibili.enabled=false",
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
    private BotConfig botConfig;

    @Autowired
    private List<AgentTaskHandler> agentTaskHandlers;

    @Autowired
    private SkillManager skillRegistry;

    @Autowired
    private BilibiliProperties bilibiliProperties;

    @Autowired
    private BilibiliContentRepository bilibiliContentRepository;

    @Autowired
    private BilibiliSubscriptionRepository bilibiliSubscriptionRepository;

    @Autowired
    private BilibiliUpdateEventRepository bilibiliUpdateEventRepository;

    @Autowired
    private BilibiliPreferenceRepository bilibiliPreferenceRepository;

    @Autowired
    private BilibiliRecommendationHistoryRepository bilibiliHistoryRepository;

    @Test
    void contextLoads() {
        assertEquals(13, toolRegistry.size());
        assertEquals(5, agentTaskHandlers.size());
        assertEquals(4, skillRegistry.size());
        assertTrue(skillRegistry.contains("bilibili"));
        assertTrue(skillRegistry.contains("document-generation"));
        assertTrue(skillRegistry.contains("voice-reply"));
        assertTrue(skillRegistry.contains("excel-operation"));
        // 处理器集合断言：不硬编码数量，改为断言关键处理器存在（新增组件不再破坏本测试）
        assertTrue(handlers.stream().anyMatch(h -> h instanceof TextMessageHandler));
        assertTrue(handlers.stream().anyMatch(h -> h instanceof ImageMessageHandler));
        assertTrue(handlers.stream().anyMatch(h -> h instanceof DocumentMessageHandler));
        assertTrue(handlers.stream().anyMatch(h -> h instanceof ExcelFileMessageHandler));
        assertTrue(handlers.stream().anyMatch(h -> h instanceof ExcelScreenshotMessageHandler));
        assertTrue(handlers.stream().anyMatch(h -> h instanceof BilibiliCommandMessageHandler));
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
        assertTrue(
            botConfig.getSystemPrompt().contains("不寒暄、不卖萌"),
            botConfig.getSystemPrompt());
        assertTrue(botConfig.getSystemPrompt().contains("API供应商"));
        assertTrue(agentOrchestrator.isConfigured() == chatService.isConfigured());
        assertFalse(bilibiliProperties.isEnabled());
        assertTrue(bilibiliProperties.getDefaultMinimumRating()
            > bilibiliProperties.getMovieMinimumRating());
        assertNotNull(bilibiliContentRepository);
        assertNotNull(bilibiliSubscriptionRepository);
        assertNotNull(bilibiliUpdateEventRepository);
        assertNotNull(bilibiliPreferenceRepository);
        assertNotNull(bilibiliHistoryRepository);
    }

}
