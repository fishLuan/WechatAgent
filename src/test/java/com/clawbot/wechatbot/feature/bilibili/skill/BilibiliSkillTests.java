package com.clawbot.wechatbot.feature.bilibili.skill;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliSkillTests {
    private static final SkillDefinition DEFINITION = new SkillDefinition(
        "bilibili", "1.0.0", true, "B站", "B站内容管理",
        "bilibili", List.of(), List.of(), 30, true);

    @Test
    void delegatesInstructionToExistingBilibiliWorkflow() {
        BilibiliCommandHandler commands = mock(BilibiliCommandHandler.class);
        when(commands.handle("user-1", "订阅牧神记")).thenReturn("订阅成功");
        SkillResult result = new BilibiliSkill(commands).execute(
            DEFINITION,
            new SkillRequest("user-1", "订阅牧神记", "", "", ""));
        assertTrue(result.success());
        verify(commands).handle("user-1", "订阅牧神记");
    }

    @Test
    void rejectsUnknownBilibiliInstruction() {
        BilibiliCommandHandler commands = mock(BilibiliCommandHandler.class);
        when(commands.handle("user-1", "模糊操作"))
            .thenReturn("[UNHANDLED-BILIBILI-UNKNOWN]");
        SkillResult result = new BilibiliSkill(commands).execute(
            DEFINITION,
            new SkillRequest("user-1", "模糊操作", "", "", ""));
        assertFalse(result.success());
    }
}
