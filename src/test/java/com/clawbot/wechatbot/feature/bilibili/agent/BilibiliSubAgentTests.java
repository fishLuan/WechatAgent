package com.clawbot.wechatbot.feature.bilibili.agent;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliSubAgentTests {

    @Test
    void executesDependentTasksInOrder() {
        BilibiliCommandHandler commands = mock(BilibiliCommandHandler.class);
        when(commands.handle("user-1", "搜索牧神记")).thenReturn("找到作品");
        when(commands.handle("user-1", "订阅第一个")).thenReturn("订阅成功");
        BilibiliSubAgent agent = new BilibiliSubAgent(
            new BilibiliTaskPlanner(), commands, 5, 3, 30);

        BilibiliAgentResult result = agent.execute(
            "user-1", "搜索牧神记，然后订阅第一个");

        assertTrue(result.success());
        assertTrue(result.text().contains("找到作品"));
        assertTrue(result.text().contains("订阅成功"));
        var ordered = inOrder(commands);
        ordered.verify(commands).handle("user-1", "搜索牧神记");
        ordered.verify(commands).handle("user-1", "订阅第一个");
    }

    @Test
    void stopsDependentWriteWhenSearchFails() {
        BilibiliCommandHandler commands = mock(BilibiliCommandHandler.class);
        when(commands.handle("user-1", "搜索牧神记")).thenReturn("❌ 搜索失败");
        BilibiliSubAgent agent = new BilibiliSubAgent(
            new BilibiliTaskPlanner(), commands, 5, 3, 30);

        BilibiliAgentResult result = agent.execute(
            "user-1", "搜索牧神记，然后订阅第一个");

        assertFalse(result.success());
        assertTrue(result.text().contains("前置B站任务失败"));
        verify(commands, never()).handle("user-1", "订阅第一个");
    }

    @Test
    void rejectsUnknownDomainInstruction() {
        BilibiliCommandHandler commands = mock(BilibiliCommandHandler.class);
        BilibiliSubAgent agent = new BilibiliSubAgent(
            new BilibiliTaskPlanner(), commands, 5, 3, 30);

        BilibiliAgentResult result = agent.execute("user-1", "随便操作一下");

        assertFalse(result.success());
        verify(commands, never()).handle("user-1", "随便操作一下");
    }
}
