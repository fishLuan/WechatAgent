package com.clawbot.wechatbot.feature.bilibili.skill;

import com.clawbot.wechatbot.feature.bilibili.agent.BilibiliAgentResult;
import com.clawbot.wechatbot.feature.bilibili.agent.BilibiliSubAgent;
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
        BilibiliSubAgent subAgent = mock(BilibiliSubAgent.class);
        when(subAgent.execute("user-1", "订阅牧神记"))
            .thenReturn(new BilibiliAgentResult(true, "订阅成功", List.of()));
        SkillResult result = new BilibiliSkill(subAgent).execute(
            DEFINITION,
            new SkillRequest("user-1", "订阅牧神记", "", "", ""));
        assertTrue(result.success());
        verify(subAgent).execute("user-1", "订阅牧神记");
    }

    @Test
    void rejectsUnknownBilibiliInstruction() {
        BilibiliSubAgent subAgent = mock(BilibiliSubAgent.class);
        when(subAgent.execute("user-1", "模糊操作"))
            .thenReturn(BilibiliAgentResult.failure("无法识别B站领域操作"));
        SkillResult result = new BilibiliSkill(subAgent).execute(
            DEFINITION,
            new SkillRequest("user-1", "模糊操作", "", "", ""));
        assertFalse(result.success());
    }
}
